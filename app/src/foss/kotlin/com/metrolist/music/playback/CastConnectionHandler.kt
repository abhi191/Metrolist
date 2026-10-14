package com.metrolist.music.playback

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.metrolist.innertube.YouTube
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.sonos.SonosClient
import com.metrolist.music.sonos.SonosDiscovery
import com.metrolist.music.sonos.SonosRoom
import com.metrolist.music.sonos.SonosSessionPlayer
import com.metrolist.music.sonos.SonosStreamServer
import com.metrolist.music.sonos.SonosTrackMetadata
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * FOSS builds have no Google Cast, so this handler drives Sonos speakers over UPnP
 * instead. It keeps the same surface as the GMS Cast handler, so the player UI and
 * MusicService route play/pause/seek/skip here unchanged while a room is connected.
 *
 * The local ExoPlayer stays the source of truth for the queue; Sonos plays one track
 * at a time, and when it finishes the local queue advances, which loads the next track.
 */
@UnstableApi
class CastConnectionHandler(
    context: Context,
    private val scope: CoroutineScope,
    private val musicService: MusicService,
) {
    private val appContext = context.applicationContext

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName.asStateFlow()

    private val _castPosition = MutableStateFlow(0L)
    val castPosition: StateFlow<Long> = _castPosition.asStateFlow()

    private val _castDuration = MutableStateFlow(0L)
    val castDuration: StateFlow<Long> = _castDuration.asStateFlow()

    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying.asStateFlow()

    private val _castIsBuffering = MutableStateFlow(false)
    val castIsBuffering: StateFlow<Boolean> = _castIsBuffering.asStateFlow()

    private val _castVolume = MutableStateFlow(1f)
    val castVolume: StateFlow<Float> = _castVolume.asStateFlow()

    val isSyncingFromCast = false

    private val _rooms = MutableStateFlow<List<SonosRoom>>(emptyList())
    val rooms: StateFlow<List<SonosRoom>> = _rooms.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _connectedRoomId = MutableStateFlow<String?>(null)
    val connectedRoomId: StateFlow<String?> = _connectedRoomId.asStateFlow()

    private val lanHttp =
        OkHttpClient
            .Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

    private val streamHttp by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }.build()
    }

    private val streamServer by lazy {
        SonosStreamServer(scope, streamHttp) { videoId ->
            musicService.getPlaybackData(videoId, requireMp4 = true)
        }
    }

    // Sonos pulls audio from this phone, so Wi-Fi must stay awake while connected.
    @Suppress("DEPRECATION")
    private val wifiLock by lazy {
        appContext
            .getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "metrolist-sonos")
            ?.apply { setReferenceCounted(false) }
    }

    private var client: SonosClient? = null
    private var sessionPlayer: SonosSessionPlayer? = null
    private var cachedWrapper: Pair<Player, SonosSessionPlayer>? = null
    private var pollCount = 0
    private var lastVolumeChangeAt = 0L
    private var currentMediaId: String? = null
    private var discoveryJob: Job? = null
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    // Set while a track is being loaded so the brief STOPPED state isn't read as "track ended".
    @Volatile
    private var awaitingStart = false
    private var loadStartedAt = 0L

    @Volatile
    private var lastState = ""

    init {
        scope.launch {
            combine(_isCasting, _castIsPlaying, _castIsBuffering, _castPosition, _castVolume) { _, _, _, _, _ -> }
                .collect { sessionPlayer?.refresh() }
        }
    }

    fun initialize() = true

    /** While connected, the media session exposes the speaker so volume keys and the notification control it. */
    fun sessionPlayerFor(player: Player): Player {
        if (!_isCasting.value) {
            sessionPlayer = null
            return player
        }
        // Reuse the wrapper for the same player: each one keeps a listener on the player it wraps.
        val wrapper =
            cachedWrapper?.takeIf { it.first === player }?.second
                ?: SonosSessionPlayer(player, this).also { cachedWrapper = player to it }
        sessionPlayer = wrapper
        wrapper.refresh()
        return wrapper
    }

    fun discover() {
        if (discoveryJob?.isActive == true) return
        discoveryJob =
            scope.launch {
                _isDiscovering.value = true
                try {
                    _rooms.value = SonosDiscovery.discover(appContext, lanHttp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Sonos discovery failed")
                } finally {
                    _isDiscovering.value = false
                }
            }
    }

    fun connect(room: SonosRoom) {
        if (_connectedRoomId.value == room.id) return
        if (_isCasting.value) disconnect()

        client = SonosClient(room.host, lanHttp)
        runCatching { wifiLock?.acquire() }
        _connectedRoomId.value = room.id
        _castDeviceName.value = room.name
        _isCasting.value = true
        _castPosition.value = musicService.player.currentPosition
        _castDuration.value = musicService.player.duration.coerceAtLeast(0L)
        musicService.player.pause()
        musicService.refreshSessionPlayer()

        scope.launch {
            runCatching { client?.volume() }
                .getOrNull()
                ?.let { _castVolume.value = it / 100f }
        }
        startPolling()
        loadCurrentMedia()
    }

    fun disconnect() {
        if (!_isCasting.value) return
        val sonos = client
        val resumeAt = _castPosition.value
        loadJob?.cancel()
        stopPolling()
        scope.launch { runCatching { sonos?.stop() } }

        client = null
        currentMediaId = null
        _isCasting.value = false
        _connectedRoomId.value = null
        _castDeviceName.value = null
        _castIsPlaying.value = false
        _castIsBuffering.value = false
        musicService.refreshSessionPlayer()
        streamServer.stop()
        runCatching { wifiLock?.release() }

        if (resumeAt > 0) musicService.player.seekTo(resumeAt)
        musicService.player.pause()
    }

    fun loadCurrentMedia() {
        musicService.currentMediaMetadata.value?.let(::loadMedia)
    }

    fun loadMedia(metadata: MediaMetadata) {
        val sonos = client ?: return
        if (!_isCasting.value) return
        loadJob?.cancel()
        loadJob =
            scope.launch {
                awaitingStart = true
                loadStartedAt = SystemClock.elapsedRealtime()
                _castIsBuffering.value = true
                try {
                    val localPlayer = musicService.player
                    val startPosition =
                        if (localPlayer.currentMediaItem?.mediaId == metadata.id) localPlayer.currentPosition else 0L
                    val uri =
                        withContext(Dispatchers.IO) {
                            streamServer.start()
                            streamServer.urlFor(sonos.host, metadata.id)
                        } ?: error("No network route to ${sonos.host}")

                    currentMediaId = metadata.id
                    _castPosition.value = startPosition
                    _castDuration.value = metadata.duration * 1000L
                    sonos.setUri(uri, metadata.toSonos())
                    sonos.play()
                    if (startPosition > SEEK_THRESHOLD_MS) seekOnceStarted(sonos, startPosition)
                    localPlayer.pause()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to play ${metadata.id} on Sonos")
                    awaitingStart = false
                    _castIsBuffering.value = false
                    _castIsPlaying.value = false
                }
            }
    }

    fun play() {
        val sonos = client ?: return
        if (currentMediaId == null) {
            loadCurrentMedia()
            return
        }
        _castIsPlaying.value = true
        scope.launch {
            runCatching { sonos.play() }.onFailure {
                Timber.w(it, "Sonos play failed, reloading track")
                loadCurrentMedia()
            }
        }
    }

    fun pause() {
        val sonos = client ?: return
        _castIsPlaying.value = false
        scope.launch { runCatching { sonos.pause() }.onFailure { Timber.w(it, "Sonos pause failed") } }
    }

    fun seekTo(position: Long) {
        val sonos = client ?: return
        _castPosition.value = position
        scope.launch { runCatching { sonos.seek(position) }.onFailure { Timber.w(it, "Sonos seek failed") } }
    }

    fun setVolume(volume: Float) {
        val sonos = client ?: return
        val clamped = volume.coerceIn(0f, 1f)
        _castVolume.value = clamped
        lastVolumeChangeAt = SystemClock.elapsedRealtime()
        scope.launch {
            runCatching { sonos.setVolume((clamped * 100).roundToInt()) }
                .onFailure { Timber.w(it, "Sonos volume change failed") }
        }
    }

    fun skipToNext() {
        val player = musicService.player
        if (!player.hasNextMediaItem()) return
        player.pause()
        player.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        val player = musicService.player
        if (_castPosition.value > RESTART_THRESHOLD_MS || !player.hasPreviousMediaItem()) {
            seekTo(0L)
            return
        }
        player.pause()
        player.seekToPreviousMediaItem()
    }

    // Sonos only ever holds the current track, so every queue jump goes through loadMedia.
    fun navigateToMediaIfInQueue(mediaId: String) = false

    fun release() {
        disconnect()
        discoveryJob?.cancel()
    }

    private suspend fun seekOnceStarted(
        sonos: SonosClient,
        position: Long,
    ) {
        repeat(20) {
            if (runCatching { sonos.transportState() }.getOrNull() == STATE_PLAYING) {
                runCatching { sonos.seek(position) }
                return
            }
            delay(250)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        lastState = ""
        pollJob =
            scope.launch {
                while (isActive && _isCasting.value) {
                    client?.let { sonos ->
                        try {
                            poll(sonos)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.v(e, "Sonos poll failed")
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun poll(sonos: SonosClient) {
        val state = sonos.transportState()
        val info = sonos.positionInfo()
        val previousState = lastState
        val previousPosition = _castPosition.value
        lastState = state

        if (state == STATE_PLAYING) {
            awaitingStart = false
        } else if (awaitingStart && SystemClock.elapsedRealtime() - loadStartedAt > LOAD_TIMEOUT_MS) {
            Timber.w("Sonos never started playing ${currentMediaId}; giving up")
            awaitingStart = false
        }
        _castIsBuffering.value = state == STATE_TRANSITIONING || awaitingStart
        _castIsPlaying.value = state == STATE_PLAYING || state == STATE_TRANSITIONING || awaitingStart
        if (info.durationMs > 0) _castDuration.value = info.durationMs
        if (state == STATE_PLAYING || state == STATE_PAUSED) _castPosition.value = info.positionMs

        // Pick up volume changes made elsewhere (Sonos app, physical buttons), but not mid-adjustment.
        if (++pollCount % VOLUME_POLL_EVERY == 0 &&
            SystemClock.elapsedRealtime() - lastVolumeChangeAt > VOLUME_SETTLE_MS
        ) {
            runCatching { sonos.volume() }.getOrNull()?.let { _castVolume.value = it / 100f }
        }

        val duration = _castDuration.value
        val reachedEnd = duration > 0 && previousPosition >= duration - END_TOLERANCE_MS
        if (!awaitingStart && previousState == STATE_PLAYING && state == STATE_STOPPED && reachedEnd) {
            onTrackEnded()
        }
    }

    private fun onTrackEnded() {
        val player = musicService.player
        when {
            player.repeatMode == Player.REPEAT_MODE_ONE -> loadCurrentMedia()
            player.hasNextMediaItem() -> player.seekToNextMediaItem()
            else -> {
                _castIsPlaying.value = false
                _castPosition.value = 0L
            }
        }
    }

    private fun MediaMetadata.toSonos() =
        SonosTrackMetadata(
            title = title,
            artist = artists.joinToString(", ") { it.name }.ifBlank { null },
            album = album?.title,
            artworkUrl = thumbnailUrl?.resize(600, 600),
        )

    private companion object {
        const val STATE_PLAYING = "PLAYING"
        const val STATE_PAUSED = "PAUSED_PLAYBACK"
        const val STATE_STOPPED = "STOPPED"
        const val STATE_TRANSITIONING = "TRANSITIONING"
        const val POLL_INTERVAL_MS = 1_000L
        const val END_TOLERANCE_MS = 3_000L
        const val SEEK_THRESHOLD_MS = 2_000L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val LOAD_TIMEOUT_MS = 20_000L
        const val VOLUME_POLL_EVERY = 3
        const val VOLUME_SETTLE_MS = 2_000L
    }
}
