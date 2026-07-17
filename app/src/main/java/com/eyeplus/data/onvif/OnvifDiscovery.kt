package com.eyeplus.data.onvif

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Semaphore

class OnvifDiscovery {

    companion object {
        private const val TAG = "OnvifDiscovery"
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val MULTICAST_PORT = 3702
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val BUFFER_SIZE = 65536
        private val SCAN_PORTS = listOf(80, 8080, 8899, 554)

        private val PROBE_MESSAGE = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope
    xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
    xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
    xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery"
    xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
  <soap:Header>
    <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
    <wsa:MessageID>urn:uuid:__UUID__</wsa:MessageID>
    <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
  </soap:Header>
  <soap:Body>
    <wsd:Probe>
      <wsd:Types>dn:NetworkVideoTransmitter</wsd:Types>
    </wsd:Probe>
  </soap:Body>
</soap:Envelope>"""

        // ONVIF GetCapabilities probe for direct camera detection
        private val GET_CAPABILITIES_SOAP = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope
    xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
    xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
  <soap:Body>
    <tds:GetCapabilities>
      <tds:Category>All</tds:Category>
    </tds:GetCapabilities>
  </soap:Body>
</soap:Envelope>"""

        private val ONVIF_PATHS = listOf(
            "/onvif/device_service",
            "/onvif/DeviceService",
            "/onvif/services/device_service",
            "/onvif/services",
            "/onvif_service",
            "/onvif"
        )
    }

    data class DiscoveredDevice(
        val ip: String,
        val port: Int = 80,
        val xaddrs: List<String> = emptyList(),
        val scopes: List<String> = emptyList(),
        val types: List<String> = emptyList(),
        val model: String = "",
        val manufacturer: String = "",
        val source: String = "ws-discovery"
    )

    suspend fun discover(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): List<DiscoveredDevice> {
        val wsDevices = wsDiscovery(minOf(timeoutMs, 3000L))
        if (wsDevices.isNotEmpty()) return wsDevices

        Log.d(TAG, "WS-Discovery found nothing, scanning subnet (TCP parallel)")
        val scanDevices = fastSubnetScan(minOf(timeoutMs, 15000L))
        if (scanDevices.isNotEmpty()) return scanDevices

        Log.d(TAG, "TCP scan found nothing, trying ARP cache")
        val arpDevices = arpCacheScan()
        if (arpDevices.isNotEmpty()) return arpDevices

        Log.w(TAG, "ALL DISCOVERY METHODS FAILED — no cameras found")
        Log.w(TAG, "Tips: phone must be on same WiFi, camera must have ONVIF enabled")
        return emptyList()
    }

    private suspend fun wsDiscovery(timeoutMs: Long): List<DiscoveredDevice> {
        return withContext(Dispatchers.IO) {
            val devices = mutableListOf<DiscoveredDevice>()
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                socket.soTimeout = timeoutMs.toInt()
                socket.bind(InetSocketAddress(0))
                val probeXml = PROBE_MESSAGE.replace("__UUID__", UUID.randomUUID().toString())
                val probeBytes = probeXml.toByteArray(Charsets.UTF_8)
                val probePacket = DatagramPacket(probeBytes, probeBytes.size,
                    InetAddress.getByName(MULTICAST_ADDRESS), MULTICAST_PORT)
                socket.send(probePacket)

                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    try {
                        val responsePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(responsePacket)
                        val xml = String(responsePacket.data, responsePacket.offset,
                            responsePacket.length, Charsets.UTF_8)
                        val senderIp = responsePacket.address.hostAddress ?: "unknown"
                        val device = parseProbeMatch(xml, senderIp)
                        if (device != null && devices.none { it.ip == device.ip }) {
                            devices.add(device.copy(source = "ws-discovery"))
                        }
                    } catch (e: java.net.SocketTimeoutException) { break }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WS-Discovery error: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
            devices
        }
    }

    private suspend fun fastSubnetScan(timeoutMs: Long = 15000L): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val devices = Collections.synchronizedList(mutableListOf<DiscoveredDevice>())
        val localIp = getLocalIp() ?: run {
            Log.w(TAG, "Cannot determine local IP — WiFi might be off or only Tailscale active")
            return@withContext devices
        }
        val subnet = localIp.substringBeforeLast('.')
        Log.d(TAG, "TCP-scanning subnet $subnet.xxx on ports ${SCAN_PORTS.joinToString()}")

        val semaphore = Semaphore(20) // max 20 concurrent connections
        val connectTimeout = 200
        val hosts = 1..254

        // Single-pass: try each host on all scan ports in parallel
        val jobs = hosts.map { host ->
            async {
                val ip = "$subnet.$host"
                var found = false
                for (port in SCAN_PORTS) {
                    if (found) break // stop scanning this host once we found it
                    semaphore.acquire()
                    try {
                        val sock = Socket()
                        sock.connect(InetSocketAddress(ip, port), connectTimeout)
                        sock.close()

                        // Port open — try to confirm it's a camera
                        var confirmed = false
                        var sourceInfo = ""

                        // 1) Fast HTTP GET check — keywords in response body
                        val body = httpGet("http://$ip:$port/")
                        if (body != null) {
                            if (body.contains("onvif", ignoreCase = true) ||
                                body.contains("eyeplus", ignoreCase = true) ||
                                body.contains("ginatex", ignoreCase = true) ||
                                body.contains("camera", ignoreCase = true) ||
                                body.contains("dvr", ignoreCase = true) ||
                                body.contains("nvr", ignoreCase = true)) {
                                confirmed = true
                                sourceInfo = "http-match"
                                Log.d(TAG, "HTTP keyword match at $ip:$port")
                            } else {
                                // No keywords but got HTTP response — potential device
                                sourceInfo = "http-any"
                            }
                        }

                        // 2) ONVIF SOAP probe — any XML/SOAP response (including 401 auth faults)
                        //    means an ONVIF endpoint exists behind that port
                        if (!confirmed) {
                            for (onvifPath in ONVIF_PATHS) {
                                val soapResponse = httpPost("http://$ip:$port$onvifPath", GET_CAPABILITIES_SOAP)
                                if (soapResponse != null) {
                                    // Check if response is SOAP/XML (ONVIF endpoint even if auth required)
                                    if (soapResponse.contains("Envelope", ignoreCase = true) ||
                                        soapResponse.contains("onvif", ignoreCase = true) ||
                                        soapResponse.contains("Capabilities", ignoreCase = true) ||
                                        soapResponse.contains("Fault", ignoreCase = true) ||
                                        soapResponse.contains("Security", ignoreCase = true)) {
                                        confirmed = true
                                        sourceInfo = "onvif-probe"
                                        Log.i(TAG, "ONVIF endpoint confirmed at $ip:$port$onvifPath" +
                                            " (HTTP ${if (body != null) "with body" else "no body"})")
                                        break
                                    }
                                }
                            }
                        }

                        // Always add TCP-open devices — ONVIF auth was the blocker before
                        devices.add(DiscoveredDevice(
                            ip = ip, port = port,
                            source = if (confirmed) "tcp-scan" else "possible"
                        ))
                        found = true
                        Log.i(TAG, "CAMERA FOUND via TCP: $ip:$port (confirmed=$confirmed, src=$sourceInfo)")

                    } catch (_: Exception) { }
                    finally { semaphore.release() }
                }
            }
        }
        jobs.awaitAll()

        Log.d(TAG, "TCP scan complete — found ${devices.size} camera(s)")
        devices.toList()
    }

    private suspend fun arpCacheScan(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<DiscoveredDevice>()
        try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.lineSequence().drop(1).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4 && parts[2] == "0x2") {
                    val ip = parts[0]
                    for (port in SCAN_PORTS) {
                        try {
                            val sock = Socket()
                            sock.connect(InetSocketAddress(ip, port), 200)
                            sock.close()
                            devices.add(DiscoveredDevice(ip = ip, port = port, source = "arp"))
                            break
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ARP scan error: ${e.message}")
        }
        devices
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            // Read body for ANY code — even 401 pages may contain camera identifiers
            val bodyStream = if (code in 200..399) conn.inputStream else conn.errorStream
            if (bodyStream != null) {
                val reader = BufferedReader(InputStreamReader(bodyStream))
                val text = reader.readText()
                reader.close()
                text
            } else null
        } catch (_: Exception) { null }
    }

    private fun httpPost(urlStr: String, soapXml: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            conn.setRequestProperty("SOAPAction", "\"http://www.onvif.org/ver10/device/wsdl/GetCapabilities\"")
            val os = conn.outputStream
            os.write(soapXml.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()
            val code = conn.responseCode
            // Read body for ANY HTTP code — ONVIF 401/403 responses still have valid SOAP XML body
            val bodyStream = if (code in 200..399) conn.inputStream else conn.errorStream
            if (bodyStream != null) {
                val reader = BufferedReader(InputStreamReader(bodyStream))
                val text = reader.readText()
                reader.close()
                text
            } else null
        } catch (_: Exception) { null }
    }

    private fun getLocalIp(): String? {
        try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && !it.hostAddress.startsWith("100.") }
                ?.let { return it.hostAddress }
        } catch (_: Exception) { }
        return null
    }

    private fun parseProbeMatch(xml: String, senderIp: String): DiscoveredDevice? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var xaddrs = mutableListOf<String>()
            var scopes = mutableListOf<String>()
            var types = mutableListOf<String>()
            var ip = senderIp; var port = 80; var model = ""; var manufacturer = ""
            var inXAddrs = false; var inScopes = false; var inTypes = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) { "XAddrs" -> inXAddrs = true; "Scopes" -> inScopes = true; "Types" -> inTypes = true }
                    }
                    XmlPullParser.TEXT -> {
                        when {
                            inXAddrs -> {
                                val addr = parser.text.trim()
                                xaddrs.add(addr)
                                val match = Regex("http://([^:/]+)(?::(\\d+))?").find(addr)
                                if (match != null) { ip = match.groupValues[1]; port = match.groupValues[2].toIntOrNull() ?: 80 }
                            }
                            inScopes -> {
                                scopes.addAll(parser.text.trim().split("\\s+".toRegex()))
                                scopes.forEach { scope -> if (scope.contains("hardware/")) model = scope.substringAfter("hardware/") }
                            }
                            inTypes -> types.addAll(parser.text.trim().split("\\s+".toRegex()))
                        }
                    }
                    XmlPullParser.END_TAG -> { when (parser.name) { "XAddrs" -> inXAddrs = false; "Scopes" -> inScopes = false; "Types" -> inTypes = false } }
                }
                parser.next()
            }
            if (types.any { it.contains("NetworkVideoTransmitter") || it.contains("onvif") }) {
                DiscoveredDevice(ip = ip, port = port, xaddrs = xaddrs, scopes = scopes, types = types, model = model, manufacturer = manufacturer)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ProbeMatch: ${e.message}")
            null
        }
    }
}
