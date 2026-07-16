package com.eyeplus.data.onvif

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class OnvifClient(
    private val host: String,
    private val port: Int = 80,
    private val username: String = "admin",
    private val password: String = "admin"
) {
    companion object {
        private const val TAG = "OnvifClient"
        private const val SOAP_MEDIA_TYPE = "application/soap+xml; charset=utf-8"
        private val SOAP_MEDIA = SOAP_MEDIA_TYPE.toMediaType()

        val WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        val WSU_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
        val SOAP_ENV_NS = "http://www.w3.org/2003/05/soap-envelope"
        val TD_NS = "http://www.onvif.org/ver10/device/wsdl"
        val TRT_NS = "http://www.onvif.org/ver10/media/wsdl"
        val TDS_NS = "http://www.onvif.org/ver20/ptz/wsdl"
        val TYPES_NS = "http://www.onvif.org/ver10/schema"

        val DEVICE_PATHS = listOf(
            "/onvif/device_service",
            "/onvif/device",
            "/onvif/DeviceService",
            "/onvif/services",
            "/ONVIF/device_service",
            "/onvif_service",
            "/DeviceService",
        )

        val MEDIA_PATHS = listOf(
            "/onvif/media_service",
            "/onvif/media",
            "/onvif/MediaService",
            "/onvif/media2_service",
        )

        val PTZ_PATHS = listOf(
            "/onvif/ptz_service",
            "/onvif/ptz",
            "/onvif/PTZService",
        )

        val RTSP_PATTERNS = listOf(
            "/0/av0",
            "/0/av1",
            "/h264_stream",
            "/h264/ch1/main/av_stream",
            "/h264/ch1/sub/av_stream",
            "/streaming/0/0",
            "/streaming/0/1",
            "/live/0/main",
            "/live/0/sub",
            "/live/ch0",
            "/cam1/mpeg4",
        )

        private val PTZ_COMMANDS = mapOf(
            "UP" to "0",
            "DOWN" to "2",
            "LEFT" to "4",
            "RIGHT" to "6",
            "STOP" to "1"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cachedProfiles = mutableListOf<MediaProfile>()
    private var discoveredDevicePath: String? = null
    private var discoveredMediaPath: String? = null
    private var discoveredPtzPath: String? = null

    private val baseUrl: String get() = "http://$host:$port"

    private fun createWsSecurityHeader(): String {
        val nonce = ByteArray(20).apply { SecureRandom().nextBytes(this) }
        val created = Instant.now().toString()
        val formattedCreated = created.replace("Z", "000Z")
        val digestInput = nonce + formattedCreated.toByteArray() + password.toByteArray()
        val digest = MessageDigest.getInstance("SHA-1").digest(digestInput)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val digestB64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        return """
            |<wsse:Security xmlns:wsse="$WSSE_NS" xmlns:wsu="$WSU_NS" mustUnderstand="true">
            |  <wsse:UsernameToken wsu:Id="UsernameToken-1">
            |    <wsse:Username>$username</wsse:Username>
            |    <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestB64</wsse:Password>
            |    <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0">$nonceB64</wsse:Nonce>
            |    <wsu:Created>$formattedCreated</wsu:Created>
            |  </wsse:UsernameToken>
            |</wsse:Security>
        """.trimMargin()
    }

    private fun createSoapEnvelope(body: String, security: Boolean = true): String {
        val header = if (security) createWsSecurityHeader() else ""
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="$SOAP_ENV_NS">
  <s:Header>$header</s:Header>
  <s:Body>$body</s:Body>
</s:Envelope>"""
    }

    private suspend fun tryPaths(bodyXml: String, paths: List<String>, requiredTag: String? = null): Pair<String, String>? {
        var lastError: String? = null
        for (path in paths) {
            Log.d(TAG, "Trying path: $path")
            val response = sendSoapRaw(path, bodyXml)
            if (response != null) {
                if (requiredTag == null || response.contains(requiredTag, ignoreCase = true)) {
                    Log.d(TAG, "Path works: $path")
                    return Pair(path, response)
                }
                lastError = "Path $path returned response without $requiredTag"
            } else {
                lastError = "Path $path failed"
            }
        }
        Log.w(TAG, "All paths failed: $lastError")
        return null
    }

    private suspend fun sendSoapRaw(endpoint: String, bodyXml: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val envelope = createSoapEnvelope(bodyXml)
                val requestBody = envelope.toRequestBody(SOAP_MEDIA)
                val request = Request.Builder()
                    .url(baseUrl + endpoint)
                    .addHeader("Content-Type", SOAP_MEDIA_TYPE)
                    .post(requestBody)
                    .build()

                Log.d(TAG, "SOAP $endpoint")
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    val code = response.code()
                    Log.d(TAG, "  HTTP $code")
                    if (code in 200..399 && body != null) {
                        body
                    } else {
                        Log.w(TAG, "  Error: $code")
                        null
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "  Timeout $endpoint")
                null
            } catch (e: ConnectException) {
                Log.w(TAG, "  Connection refused $endpoint")
                null
            } catch (e: UnknownHostException) {
                Log.w(TAG, "  Unknown host: $host")
                null
            } catch (e: Exception) {
                Log.e(TAG, "  Error $endpoint: ${e.message}")
                null
            }
        }
    }

    private suspend fun sendSoap(endpoint: String, bodyXml: String): String? {
        return sendSoapRaw(endpoint, bodyXml)
    }

    suspend fun testConnection(): ConnectionTestResult {
        val deviceResult = tryPaths(
            """<GetDeviceInformation xmlns="$TD_NS"/>""",
            DEVICE_PATHS,
            requiredTag = "Manufacturer"
        )

        if (deviceResult != null) {
            discoveredDevicePath = deviceResult.first
            Log.d(TAG, "Device service found at: $discoveredDevicePath")
            return ConnectionTestResult(true, "Device service at $discoveredDevicePath")
        }

        val probeResult = tryPaths(
            """<GetServices xmlns="$TD_NS"><IncludeCapability>true</IncludeCapability></GetServices>""",
            DEVICE_PATHS
        )

        if (probeResult != null) {
            discoveredDevicePath = probeResult.first
            Log.d(TAG, "Device service found via GetServices: $discoveredDevicePath")
            return ConnectionTestResult(true, "Device service at $discoveredDevicePath")
        }

        val probeMedia = tryPaths(
            """<GetProfiles xmlns="$TRT_NS"/>""",
            DEVICE_PATHS.flatMap { devPath -> MEDIA_PATHS.map { it } }.distinct(),
            requiredTag = "Profiles"
        )

        if (probeMedia != null) {
            discoveredMediaPath = probeMedia.first
            Log.d(TAG, "Media service found: $discoveredMediaPath")
            return ConnectionTestResult(true, "Media service at $discoveredMediaPath")
        }

        return ConnectionTestResult(false, "No ONVIF endpoint found on $host:$port")
    }

    data class ConnectionTestResult(
        val success: Boolean,
        val message: String
    )

    suspend fun getDeviceInformation(): Map<String, String> {
        val path = discoveredDevicePath ?: DEVICE_PATHS.first()
        val body = """<GetDeviceInformation xmlns="$TD_NS"/>"""
        val response = tryPaths(body, listOf(path))?.second ?: sendSoap(path, body) ?: return emptyMap()
        return parseDeviceInfo(response)
    }

    private fun parseDeviceInfo(xml: String): Map<String, String> {
        val info = mutableMapOf<String, String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var currentTag = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.name
                } else if (parser.eventType == XmlPullParser.TEXT) {
                    when (currentTag) {
                        "Manufacturer", "Model", "FirmwareVersion",
                        "SerialNumber", "HardwareId" -> info[currentTag] = parser.text
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse device info: ${e.message}")
        }
        return info
    }

    suspend fun getProfiles(): List<MediaProfile> {
        if (cachedProfiles.isNotEmpty()) return cachedProfiles
        val paths = (discoveredMediaPath?.let { listOf(it) } ?: MEDIA_PATHS)
        for (path in paths) {
            val body = """<GetProfiles xmlns="$TRT_NS"/>"""
            val result = tryPaths(body, listOf(path), requiredTag = "Profiles")
            if (result != null) {
                discoveredMediaPath = path
                cachedProfiles.addAll(parseProfiles(result.second))
                return cachedProfiles.toList()
            }
        }
        return emptyList()
    }

    private fun parseProfiles(xml: String): List<MediaProfile> {
        val profiles = mutableListOf<MediaProfile>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var token: String? = null
            var name: String? = null
            var videoSource: String? = null
            var videoEncoder: String? = null
            var audioEncoder: String? = null
            var ptzConfig: String? = null
            var inProfile = false
            var inVideoSource = false
            var inVideoEncoder = false
            var inAudioEncoder = false
            var inPTZConfig = false
            var currentTag = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        when (parser.name) {
                            "Profiles" -> {
                                inProfile = true
                                token = parser.getAttributeValue(null, "token")
                                name = null; videoSource = null; videoEncoder = null; audioEncoder = null; ptzConfig = null
                            }
                            "VideoSource" -> inVideoSource = true
                            "VideoEncoderConfiguration" -> inVideoEncoder = true
                            "AudioEncoderConfiguration" -> inAudioEncoder = true
                            "PTZConfiguration" -> inPTZConfig = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        when {
                            inProfile && currentTag == "Name" -> name = parser.text
                            inVideoSource && currentTag == "token" -> videoSource = parser.text
                            inVideoEncoder && currentTag == "token" -> videoEncoder = parser.text
                            inAudioEncoder && currentTag == "token" -> audioEncoder = parser.text
                            inPTZConfig && currentTag == "token" -> ptzConfig = parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "VideoSource" -> inVideoSource = false
                            "VideoEncoderConfiguration" -> inVideoEncoder = false
                            "AudioEncoderConfiguration" -> inAudioEncoder = false
                            "PTZConfiguration" -> inPTZConfig = false
                            "Profiles" -> {
                                if (inProfile && token != null) {
                                    profiles.add(MediaProfile(token, name ?: "Profile $token", videoSource, videoEncoder, audioEncoder, ptzConfig))
                                }
                                inProfile = false
                            }
                        }
                        currentTag = ""
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profiles: ${e.message}")
        }
        return profiles
    }

    suspend fun getStreamUri(profileToken: String, protocol: String = "RTSP"): StreamUri? {
        val paths = (discoveredMediaPath?.let { listOf(it) } ?: MEDIA_PATHS)
        val body = """<GetStreamUri xmlns="$TRT_NS"><StreamSetup><Stream xmlns="$TYPES_NS">RTP-Unicast</Stream><Transport xmlns="$TYPES_NS"><Protocol>$protocol</Protocol></Transport></StreamSetup><ProfileToken>$profileToken</ProfileToken></GetStreamUri>"""
        for (path in paths) {
            val response = sendSoap(path, body)
            if (response != null) {
                val uri = parseStreamUri(response)
                if (uri != null) return uri
            }
        }
        return null
    }

    private fun parseStreamUri(xml: String): StreamUri? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var uri: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.TEXT && parser.name == "Uri") {
                    uri = parser.text
                }
                parser.next()
            }
            uri?.let { StreamUri(uri = it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse StreamUri: ${e.message}")
            null
        }
    }

    suspend fun getRtspUrl(profileIndex: Int = 0): String {
        val profiles = getProfiles()
        if (profiles.isNotEmpty() && profileIndex < profiles.size) {
            val streamUri = getStreamUri(profiles[profileIndex].token)
            if (streamUri != null) {
                Log.d(TAG, "ONVIF returned RTSP URL: ${streamUri.uri}")
                return streamUri.uri
            }
        }

        for (pattern in RTSP_PATTERNS) {
            val url = "rtsp://$username:$password@$host:554$pattern"
            Log.d(TAG, "Trying RTSP pattern: $url")
        }

        return when (profileIndex) {
            0 -> "rtsp://$username:$password@$host:554/0/av0"
            1 -> "rtsp://$username:$password@$host:554/0/av1"
            else -> "rtsp://$username:$password@$host:554/0/av0"
        }
    }

    suspend fun relativeMove(panX: Float, tiltY: Float, zoomX: Float? = null, profileToken: String? = null): Boolean {
        val token = profileToken ?: getFirstProfileToken() ?: return false
        val zoomXml = if (zoomX != null) """<Zoom x="$zoomX" space="http://www.onvif.org/ver10/ptz/space/RelativePositionTranslation"/>""" else ""
        val zoomSpeedXml = if (zoomX != null) """<Zoom x="1.0" space="http://www.onvif.org/ver10/ptz/space/RelativeSpeed"/>""" else ""
        val body = """<RelativeMove xmlns="$TDS_NS"><ProfileToken>$token</ProfileToken><Translation><PanTilt x="$panX" y="$tiltY" space="http://www.onvif.org/ver10/ptz/space/RelativePositionTranslation"/>$zoomXml</Translation><Speed><PanTilt x="1.0" y="1.0" space="http://www.onvif.org/ver10/ptz/space/RelativeSpeed"/>$zoomSpeedXml</Speed></RelativeMove>"""
        val paths = (discoveredPtzPath?.let { listOf(it) } ?: PTZ_PATHS)
        for (path in paths) {
            val response = sendSoap(path, body)
            if (response != null) {
                discoveredPtzPath = path
                return true
            }
        }
        return false
    }

    suspend fun stopPtz(profileToken: String? = null): Boolean {
        val token = profileToken ?: getFirstProfileToken() ?: return false
        val body = """<Stop xmlns="$TDS_NS"><ProfileToken>$token</ProfileToken><PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>"""
        val paths = (discoveredPtzPath?.let { listOf(it) } ?: PTZ_PATHS)
        for (path in paths) {
            val response = sendSoap(path, body)
            if (response != null) {
                discoveredPtzPath = path
                return true
            }
        }
        return false
    }

    suspend fun getPtzStatus(profileToken: String? = null): PtzPosition? {
        val token = profileToken ?: getFirstProfileToken() ?: return null
        val body = """<GetStatus xmlns="$TDS_NS"><ProfileToken>$token</ProfileToken></GetStatus>"""
        val paths = (discoveredPtzPath?.let { listOf(it) } ?: PTZ_PATHS)
        for (path in paths) {
            val response = sendSoap(path, body)
            if (response != null) {
                discoveredPtzPath = path
                return parsePtzStatus(response)
            }
        }
        return null
    }

    private fun parsePtzStatus(xml: String): PtzPosition? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var x = 0f; var y = 0f; var zoom = 0f; var inPanTilt = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> { if (parser.name == "PanTilt") inPanTilt = true }
                    XmlPullParser.TEXT -> {
                        when { inPanTilt && parser.name == "x" -> x = parser.text.toFloatOrNull() ?: 0f
                            inPanTilt && parser.name == "y" -> y = parser.text.toFloatOrNull() ?: 0f
                            parser.name == "x" && !inPanTilt -> zoom = parser.text.toFloatOrNull() ?: 0f }
                    }
                    XmlPullParser.END_TAG -> { if (parser.name == "PanTilt") inPanTilt = false }
                }
                parser.next()
            }
            PtzPosition(x, y, zoom)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PTZ status: ${e.message}")
            null
        }
    }

    suspend fun cgiPtzCommand(command: String): Boolean {
        val code = PTZ_COMMANDS[command.uppercase()] ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/decoder_control.cgi?command=$code"
                val auth = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                val request = Request.Builder().url(url)
                    .addHeader("Authorization", "Basic $auth").get().build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.e(TAG, "CGI PTZ command failed: ${e.message}")
                false
            }
        }
    }

    private suspend fun getFirstProfileToken(): String? {
        return getProfiles().firstOrNull()?.token
    }
}
