package com.metrolist.music.sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds Sonos players via SSDP, then reads the household topology from the first
 * player that answers so grouped rooms show up once, addressed by their coordinator.
 */
object SonosDiscovery {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

    suspend fun discover(
        context: Context,
        http: OkHttpClient,
        timeoutMs: Long = 3_000,
    ): List<SonosRoom> {
        val hosts = searchHosts(context, timeoutMs)
        for (host in hosts) {
            runCatching { SonosClient(host, http).zoneGroups() }
                .onSuccess { rooms -> if (rooms.isNotEmpty()) return rooms }
                .onFailure { Timber.w(it, "Sonos topology query failed on $host") }
        }
        return emptyList()
    }

    private suspend fun searchHosts(
        context: Context,
        timeoutMs: Long,
    ): Set<String> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock =
                wifi?.createMulticastLock("metrolist-sonos")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            val hosts = linkedSetOf<String>()
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 400
                    val message =
                        (
                            "M-SEARCH * HTTP/1.1\r\n" +
                                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                                "MAN: \"ssdp:discover\"\r\n" +
                                "MX: 1\r\n" +
                                "ST: $SEARCH_TARGET\r\n\r\n"
                        ).toByteArray()
                    val group = InetAddress.getByName(SSDP_ADDRESS)
                    repeat(2) { socket.send(DatagramPacket(message, message.size, group, SSDP_PORT)) }

                    val deadline = SystemClock.elapsedRealtime() + timeoutMs
                    val buffer = ByteArray(2048)
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            if (hosts.isNotEmpty()) break
                            continue
                        }
                        val reply = String(packet.data, 0, packet.length)
                        if (reply.contains("Sonos", ignoreCase = true) || reply.contains("ZonePlayer")) {
                            packet.address?.hostAddress?.let(hosts::add)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "SSDP search failed")
            } finally {
                lock?.release()
            }
            hosts
        }
}
