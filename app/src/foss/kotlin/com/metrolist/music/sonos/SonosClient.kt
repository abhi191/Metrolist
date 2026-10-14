package com.metrolist.music.sonos

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URI

data class SonosRoom(
    val id: String,
    val name: String,
    val host: String,
)

data class SonosTrackMetadata(
    val title: String,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
)

class SonosException(
    message: String,
    val upnpErrorCode: Int? = null,
) : IOException(message)

/**
 * Minimal UPnP/SOAP client for a Sonos group coordinator (port 1400).
 */
class SonosClient(
    val host: String,
    private val http: OkHttpClient,
) {
    suspend fun zoneGroups(): List<SonosRoom> {
        val response = soap(TOPOLOGY, "GetZoneGroupState")
        val state = response.tagValue("ZoneGroupState") ?: return emptyList()
        return parseZoneGroups(state)
    }

    suspend fun setUri(
        uri: String,
        metadata: SonosTrackMetadata,
    ) {
        soap(
            AV_TRANSPORT,
            "SetAVTransportURI",
            "InstanceID" to "0",
            "CurrentURI" to uri,
            "CurrentURIMetaData" to didl(uri, metadata),
        )
    }

    suspend fun play() {
        soap(AV_TRANSPORT, "Play", "InstanceID" to "0", "Speed" to "1")
    }

    suspend fun pause() {
        soap(AV_TRANSPORT, "Pause", "InstanceID" to "0")
    }

    suspend fun stop() {
        soap(AV_TRANSPORT, "Stop", "InstanceID" to "0")
    }

    suspend fun seek(positionMs: Long) {
        soap(
            AV_TRANSPORT,
            "Seek",
            "InstanceID" to "0",
            "Unit" to "REL_TIME",
            "Target" to formatTime(positionMs),
        )
    }

    suspend fun transportState(): String =
        soap(AV_TRANSPORT, "GetTransportInfo", "InstanceID" to "0")
            .tagValue("CurrentTransportState")
            .orEmpty()

    suspend fun positionInfo(): PositionInfo {
        val response = soap(AV_TRANSPORT, "GetPositionInfo", "InstanceID" to "0")
        return PositionInfo(
            positionMs = parseTime(response.tagValue("RelTime")),
            durationMs = parseTime(response.tagValue("TrackDuration")),
            trackUri = response.tagValue("TrackURI").orEmpty(),
        )
    }

    suspend fun volume(): Int =
        runCatching {
            soap(GROUP_RENDERING, "GetGroupVolume", "InstanceID" to "0").tagValue("CurrentVolume")
        }.getOrNull()?.toIntOrNull()
            ?: soap(RENDERING, "GetVolume", "InstanceID" to "0", "Channel" to "Master")
                .tagValue("CurrentVolume")
                ?.toIntOrNull()
            ?: 0

    suspend fun setVolume(volume: Int) {
        val target = volume.coerceIn(0, 100).toString()
        runCatching {
            soap(GROUP_RENDERING, "SetGroupVolume", "InstanceID" to "0", "DesiredVolume" to target)
        }.onFailure {
            soap(RENDERING, "SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to target)
        }
    }

    data class PositionInfo(
        val positionMs: Long,
        val durationMs: Long,
        val trackUri: String,
    )

    private suspend fun soap(
        service: Service,
        action: String,
        vararg args: Pair<String, String>,
    ): String =
        withContext(Dispatchers.IO) {
            val body =
                buildString {
                    append("""<?xml version="1.0" encoding="utf-8"?>""")
                    append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
                    append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
                    append("""<u:$action xmlns:u="${service.urn}">""")
                    args.forEach { (name, value) -> append("<$name>${value.xmlEscape()}</$name>") }
                    append("</u:$action></s:Body></s:Envelope>")
                }
            val request =
                Request
                    .Builder()
                    .url("http://$host:$PORT${service.path}")
                    .header("SOAPACTION", "\"${service.urn}#$action\"")
                    .post(body.toRequestBody(SOAP_MEDIA_TYPE))
                    .build()
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    val code = text.tagValue("errorCode")?.toIntOrNull()
                    throw SonosException("$action failed (HTTP ${response.code}, UPnP $code)", code)
                }
                text
            }
        }

    private class Service(
        val path: String,
        val urn: String,
    )

    companion object {
        const val PORT = 1400

        private val SOAP_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

        private val AV_TRANSPORT =
            Service("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1")
        private val RENDERING =
            Service("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1")
        private val GROUP_RENDERING =
            Service("/MediaRenderer/GroupRenderingControl/Control", "urn:schemas-upnp-org:service:GroupRenderingControl:1")
        private val TOPOLOGY =
            Service("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1")

        private fun didl(
            uri: String,
            metadata: SonosTrackMetadata,
        ): String =
            buildString {
                append("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """)
                append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
                append("""xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" """)
                append("""xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""")
                append("""<item id="-1" parentID="-1" restricted="true">""")
                append("""<res protocolInfo="http-get:*:audio/mp4:*">${uri.xmlEscape()}</res>""")
                append("<dc:title>${metadata.title.xmlEscape()}</dc:title>")
                metadata.artist?.let {
                    append("<dc:creator>${it.xmlEscape()}</dc:creator>")
                    append("<upnp:artist>${it.xmlEscape()}</upnp:artist>")
                }
                metadata.album?.let { append("<upnp:album>${it.xmlEscape()}</upnp:album>") }
                metadata.artworkUrl?.let { append("<upnp:albumArtURI>${it.xmlEscape()}</upnp:albumArtURI>") }
                append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
                append("</item></DIDL-Lite>")
            }

        internal fun parseZoneGroups(stateXml: String): List<SonosRoom> {
            data class Member(
                val uuid: String,
                val name: String,
                val host: String?,
                val visible: Boolean,
            )

            val rooms = mutableListOf<SonosRoom>()
            val parser = Xml.newPullParser().apply { setInput(StringReader(stateXml)) }
            var coordinator: String? = null
            var members = mutableListOf<Member>()

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "ZoneGroup" -> {
                        coordinator = parser.getAttributeValue(null, "Coordinator")
                        members = mutableListOf()
                    }

                    parser.eventType == XmlPullParser.START_TAG && parser.name == "ZoneGroupMember" -> {
                        members +=
                            Member(
                                uuid = parser.getAttributeValue(null, "UUID").orEmpty(),
                                name = parser.getAttributeValue(null, "ZoneName").orEmpty(),
                                host =
                                    parser.getAttributeValue(null, "Location")?.let {
                                        runCatching { URI(it).host }.getOrNull()
                                    },
                                visible =
                                    parser.getAttributeValue(null, "Invisible") != "1" &&
                                        parser.getAttributeValue(null, "IsZoneBridge") != "1",
                            )
                    }

                    parser.eventType == XmlPullParser.END_TAG && parser.name == "ZoneGroup" -> {
                        val lead = members.firstOrNull { it.uuid == coordinator }
                        val visible = members.filter { it.visible }.distinctBy { it.name }
                        if (lead?.host != null && visible.isNotEmpty()) {
                            val others = visible.size - 1
                            rooms +=
                                SonosRoom(
                                    id = lead.uuid,
                                    name = if (others > 0) "${lead.name} + $others" else lead.name,
                                    host = lead.host,
                                )
                        }
                    }
                }
            }
            return rooms.sortedBy { it.name.lowercase() }
        }

        internal fun formatTime(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
        }

        internal fun parseTime(value: String?): Long {
            val parts = value?.split(":")?.mapNotNull { it.substringBefore('.').toLongOrNull() } ?: return 0L
            if (parts.size != 3) return 0L
            return ((parts[0] * 3600) + (parts[1] * 60) + parts[2]) * 1000
        }

        private fun String.tagValue(tag: String): String? =
            Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                .find(this)
                ?.groupValues
                ?.get(1)
                ?.xmlUnescape()

        private fun String.xmlEscape(): String =
            replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

        private fun String.xmlUnescape(): String =
            replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
    }
}
