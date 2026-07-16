package com.eyeplus.data.onvif

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    suspend fun discover(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<DiscoveredDevice> {
        val wsDevices = wsDiscovery(timeoutMs)
        if (wsDevices.isNotEmpty()) return wsDevices

        Log.d(TAG, "WS-Discovery found nothing, scanning subnet")
        val scanDevices = fastSubnetScan()
        if (scanDevices.isNotEmpty()) return scanDevices

        Log.d(TAG, "Subnet scan found nothing, trying ARP cache")
        return arpCacheScan()
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

    private suspend fun fastSubnetScan(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<DiscoveredDevice>()
        val localIp = getLocalIp() ?: return@withContext devices
        val subnet = localIp.substringBeforeLast('.')
        Log.d(TAG, "Pinging subnet $subnet.xxx")

        val liveHosts = mutableListOf<String>()
        for (host in 1..254) {
            val ip = "$subnet.$host"
            try {
                if (InetAddress.getByName(ip).isReachable(200)) {
                    liveHosts.add(ip)
                    Log.d(TAG, "Live host: $ip")
                }
            } catch (_: Exception) { }
        }

        Log.d(TAG, "Found ${liveHosts.size} live hosts, probing ONVIF ports")
        for (ip in liveHosts) {
            for (port in SCAN_PORTS) {
                try {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(ip, port), 200)
                    sock.close()
                    val response = httpGet("http://$ip:$port/")
                    if (response != null && (response.contains("onvif", ignoreCase = true) ||
                            response.contains("eyeplus", ignoreCase = true) ||
                            response.contains("ginatex", ignoreCase = true) ||
                            response.contains("camera", ignoreCase = true) ||
                            response.contains("device", ignoreCase = true))) {
                        devices.add(DiscoveredDevice(ip = ip, port = port, source = "tcp-scan"))
                        Log.d(TAG, "Found camera: $ip:$port")
                    }
                } catch (_: Exception) { }
            }
        }
        devices
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
            if (code in 200..399) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
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
