package com.metrolist.music.sonos

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.music.playback.CastConnectionHandler
import kotlin.math.roundToInt

/**
 * Session-facing view of the local player while a Sonos room is connected.
 *
 * Reporting a remote [DeviceInfo] makes Media3 publish a remote volume provider, so the
 * phone's volume buttons and the system volume panel adjust the speaker instead of the
 * phone. Play/pause from the notification, lock screen and headsets is routed to the
 * speaker too. Everything else (queue, metadata, skips) still comes from the local player.
 */
@UnstableApi
class SonosSessionPlayer(
    player: Player,
    private val handler: CastConnectionHandler,
) : ForwardingSimpleBasePlayer(player) {
    fun refresh() {
        if (Looper.myLooper() == applicationLooper) invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        val volume = (handler.castVolume.value * VOLUME_STEPS).roundToInt().coerceIn(0, VOLUME_STEPS)
        val builder =
            state
                .buildUpon()
                .setDeviceInfo(
                    DeviceInfo
                        .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                        .setMinVolume(0)
                        .setMaxVolume(VOLUME_STEPS)
                        .build(),
                ).setDeviceVolume(volume)
                .setIsDeviceMuted(false)
                .setAvailableCommands(
                    state.availableCommands
                        .buildUpon()
                        .addAll(
                            Player.COMMAND_GET_DEVICE_VOLUME,
                            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                        ).build(),
                )

        if (!state.timeline.isEmpty) {
            val position = handler.castPosition.value
            val playing = handler.castIsPlaying.value
            val buffering = handler.castIsBuffering.value
            val positionSupplier =
                if (playing && !buffering) {
                    PositionSupplier.getExtrapolating(position, 1f)
                } else {
                    PositionSupplier.getConstant(position)
                }
            builder
                .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setPlaybackState(if (buffering) Player.STATE_BUFFERING else Player.STATE_READY)
                .setIsLoading(false)
                .setContentPositionMs(positionSupplier)
                .setContentBufferedPositionMs(positionSupplier)
                .setTotalBufferedDurationMs(PositionSupplier.ZERO)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) handler.play() else handler.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) {
            handler.seekTo(positionMs.coerceAtLeast(0L))
            return Futures.immediateVoidFuture()
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleSetDeviceVolume(
        deviceVolume: Int,
        flags: Int,
    ): ListenableFuture<*> {
        handler.setVolume(deviceVolume.toFloat() / VOLUME_STEPS)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        handler.setVolume(handler.castVolume.value + 1f / VOLUME_STEPS)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        handler.setVolume(handler.castVolume.value - 1f / VOLUME_STEPS)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(
        muted: Boolean,
        flags: Int,
    ): ListenableFuture<*> {
        if (muted) handler.setVolume(0f)
        return Futures.immediateVoidFuture()
    }

    private companion object {
        // Volume buttons move one step per press; 25 steps gives 4% per press on Sonos's 0-100 scale.
        const val VOLUME_STEPS = 25
    }
}
