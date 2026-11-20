package cn.izis.izisbridge

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

enum class ConnectionState(val label: String) {
    NOT_CONFIGURED("Not configured"), UNTESTED("Configured but not tested"), CONNECTING("Connecting"),
    CONNECTED("Connected, analysis not yet verified"), READY("Ready — analysis verified"), BUSY("Busy / rate-limited"),
    DISCONNECTED("Disconnected / network unavailable"), AUTH_ERROR("Authentication error"),
    EXHAUSTED("Allowance exhausted"), UNSUPPORTED("Incompatible or unsupported backend")
}

data class ProfileStatus(
    var state: ConnectionState = ConnectionState.NOT_CONFIGURED,
    var configuration: String = "Not configured", var network: String = "Not checked",
    var authentication: String = "Not checked", var analysis: String = "Not verified",
    var quota: String = "Usage unavailable", var message: String = "", var lastCheck: Long = 0
)

internal fun providerState(code: Int, errorCode: String = ""): ConnectionState = when {
    errorCode in setOf("QUOTA_EXCEEDED", "INSUFFICIENT_CREDITS", "MONTHLY_QUOTA_EXCEEDED", "ALLOWANCE_EXHAUSTED") || code == 402 -> ConnectionState.EXHAUSTED
    errorCode in setOf("INVALID_API_KEY", "UNAUTHORIZED", "TOKEN_EXPIRED") || code in setOf(401, 403) -> ConnectionState.AUTH_ERROR
    errorCode == "CONCURRENCY_LIMIT_EXCEEDED" || code == 429 -> ConnectionState.BUSY
    code >= 500 || code == 0 -> ConnectionState.DISCONNECTED
    else -> ConnectionState.UNSUPPORTED
}

internal fun validateCloudCredential(value: String): String {
    val credential = value.trim()
    require(credential.matches(Regex("kg_live_[A-Za-z0-9_-]+")) ||
        credential.matches(Regex("wss://read19\\.com/v1/katago/[A-Za-z0-9_-]+"))) {
        "Use a Read19 kg_live_ analysis key or the account-generated secure KaTrain URL"
    }
    return credential
}

internal fun usageText(value: JSONObject): String {
    require(value.opt("plan") is JSONObject && value.opt("used") is Number && value.opt("remaining") is Number)
    val used = (value.get("used") as Number).toDouble()
    val remaining = (value.get("remaining") as Number).toDouble()
    require(used.isFinite() && remaining.isFinite() && used >= 0 && remaining >= 0 && used % 1 == 0.0 && remaining % 1 == 0.0)
    if (value.has("credits")) require(value.opt("credits") is JSONObject)
    // The OpenAPI leaves plan/credits open-ended and defines no reset field. Preserve provider labels.
    return "Consumed: ${value.get("used")}\nRemaining: ${value.get("remaining")}\nProvider plan / allowance:\n${value.getJSONObject("plan").toString(2)}" +
        (value.optJSONObject("credits")?.let { "\nProvider permanent credits:\n${it.toString(2)}" } ?: "") +
        "\nOther provider fields:\n" + JSONObject(value.toString()).apply { remove("plan"); remove("used"); remove("remaining"); remove("credits") }.toString(2)
}

internal fun rankedMoves(moves: JSONArray): JSONArray = JSONArray(
    (0 until moves.length()).map { moves.getJSONObject(it) }.sortedBy { it.optInt("order", Int.MAX_VALUE) }
)

class Read19Connection(private val changed: () -> Unit, private val completed: (String, JSONObject?) -> Unit) {
    val status = ProfileStatus()
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private var socket: WebSocket? = null
    private var credential = ""
    private val pending = ConcurrentHashMap<String, Long>()
    @Volatile private var generation = 0
    @Volatile var connected = false
        private set
    var lastUsage: JSONObject? = null
    var usageTime = 0L
    private var lastUsageAttempt = 0L
    private var usageCall: Call? = null

    @Synchronized fun configure(value: String) {
        disconnect()
        credential = validateCloudCredential(value)
        lastUsage = null; usageTime = 0; lastUsageAttempt = 0
        status.apply { state = ConnectionState.UNTESTED; configuration = "Configured"; authentication = "Not checked"; analysis = "Not verified"; quota = "Usage unavailable"; lastCheck = 0; message = "" }
        changed()
    }
    @Synchronized fun connect() {
        if (credential.isEmpty() || socket != null) return
        val version = ++generation
        status.apply { state = ConnectionState.CONNECTING; message = ""; network = "Connecting"; analysis = "Not verified" }
        changed()
        val request = Request.Builder().url(if (credential.startsWith("wss://")) credential else "wss://read19.com/v1/katago")
        if (!credential.startsWith("wss://")) request.header("Authorization", "Bearer $credential")
        socket = client.newWebSocket(request.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@Read19Connection) {
                    if (version != generation) { webSocket.cancel(); return }
                    connected = true
                    status.apply { state = ConnectionState.CONNECTED; network = "Connected"; authentication = "Accepted WebSocket handshake"; lastCheck = System.currentTimeMillis() }
                    changed()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(this@Read19Connection) {
                    if (version != generation) return
                    try {
                        require(text.length < 2_000_000)
                        val result = JSONObject(text)
                        val id = result.optString("id")
                        if (!pending.containsKey(id)) return
                        if (result.has("error") || result.has("errorCode")) {
                            pending.remove(id)
                            status.state = providerState(400, result.optString("errorCode", result.optJSONObject("error")?.optString("code") ?: ""))
                            status.message = safeError(status.state)
                            completed(id, null)
                        } else if (result.has("rootInfo") && result.has("moveInfos") && !result.optBoolean("isDuringSearch", false)) {
                            require(result.getJSONObject("rootInfo").optInt("visits") > 0)
                            result.put("moveInfos", rankedMoves(result.getJSONArray("moveInfos")))
                            pending.remove(id)
                            status.apply { state = ConnectionState.READY; analysis = "Successful response"; lastCheck = System.currentTimeMillis(); message = "" }
                            completed(id, result)
                        } else return
                        refreshUsage()
                        changed()
                    } catch (_: Exception) {
                        status.state = ConnectionState.UNSUPPORTED
                        status.message = "Invalid analysis response"
                        changed()
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failed(version, response?.code ?: 0)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failed(version, if (code in setOf(1008, 4401, 4403)) 401 else 0)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
    }
    private fun safeError(state: ConnectionState) = when (state) {
        ConnectionState.AUTH_ERROR -> "Key expired, revoked or rejected. Replace the credential."
        ConnectionState.EXHAUSTED -> "Provider allowance exhausted. The game is saved; review account usage."
        ConnectionState.BUSY -> "Provider concurrency or rate limit. Wait, then retry analysis."
        ConnectionState.UNSUPPORTED -> "Provider rejected the analysis request or returned an unsupported response."
        else -> "Network or provider unavailable. The game is saved; reconnect to continue."
    }
    @Synchronized private fun failed(version: Int, code: Int) {
        if (version != generation) return
        socket = null; connected = false
        status.state = providerState(code)
        status.network = "Disconnected"
        if (status.state == ConnectionState.AUTH_ERROR) status.authentication = "Rejected"
        status.message = safeError(status.state)
        val ids = pending.keys.toList(); pending.clear(); ids.forEach { completed(it, null) }
        changed()
    }
    @Synchronized fun analyze(query: JSONObject): String {
        check(connected && pending.isEmpty()) { "Connect and wait for the current analysis to finish" }
        val id = UUID.randomUUID().toString()
        query.put("id", id)
        pending[id] = System.currentTimeMillis()
        if (socket?.send(query.toString()) != true) { pending.remove(id); error("Analysis could not be sent") }
        status.analysis = "Analyzing"
        changed()
        return id
    }
    @Synchronized fun checkTimeout() {
        if (pending.values.any { System.currentTimeMillis() - it > 90_000 }) {
            disconnect()
            status.message = "Analysis timed out; reconnect and retry. The game is saved."
            changed()
        }
    }
    @Synchronized fun refreshUsage(manual: Boolean = false) {
        if (credential.isEmpty()) return
        if (credential.startsWith("wss://")) {
            status.quota = "Usage unavailable — compatibility URL. Configure a kg_live_ key with usage:read."
            changed(); return
        }
        val now = System.currentTimeMillis()
        if (usageCall != null || now - lastUsageAttempt < (if (manual) 5000 else 30_000)) return
        lastUsageAttempt = now
        val version = generation
        val call = client.newCall(Request.Builder().url("https://read19.com/v1/usage").header("Authorization", "Bearer $credential").build())
        usageCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finishUsage(version, null, 0)
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val value = try {
                        if (response.isSuccessful) JSONObject(response.body?.string()?.takeIf { it.length < 128_000 } ?: "") else null
                    } catch (_: Exception) { null }
                    finishUsage(version, value, response.code)
                }
            }
        })
    }
    @Synchronized private fun finishUsage(version: Int, value: JSONObject?, code: Int) {
        if (version != generation) return
        usageCall = null
        try {
            require(value != null && code == 200)
            status.quota = usageText(value)
            lastUsage = value; usageTime = System.currentTimeMillis()
            status.authentication = "usage:read accepted"
        } catch (_: Exception) {
            if (code in setOf(401, 403)) {
                status.authentication = "Usage credential rejected or usage:read unavailable"
                status.state = ConnectionState.AUTH_ERROR
            }
            status.quota = (lastUsage?.let { usageText(it) + "\nLast successful value retained" } ?: "Usage unavailable") +
                if (code in setOf(401, 403)) "\nCredential rejected or usage:read unavailable" else "\nRefresh failed"
        }
        changed()
    }
    @Synchronized fun disconnect() {
        generation++; socket?.cancel(); socket = null; connected = false
        usageCall?.cancel(); usageCall = null
        val ids = pending.keys.toList(); pending.clear(); ids.forEach { completed(it, null) }
        status.state = if (credential.isEmpty()) ConnectionState.NOT_CONFIGURED else ConnectionState.DISCONNECTED
        status.network = "Disconnected"
        changed()
    }
}

class DesktopConnection(host: String, port: Int, fingerprint: String, private val token: String) {
    val hostname = host.trim()
    private val pin = fingerprint.lowercase().replace(":", "")
    private val url: HttpUrl
    private val client: OkHttpClient
    init {
        require(pin.matches(Regex("[a-f0-9]{64}")) && token.matches(Regex("[A-Za-z0-9_-]{40,80}"))) { "Enter the desktop certificate fingerprint and pairing key" }
        require(port in 1024..65535 && host.isNotBlank())
        url = HttpUrl.Builder().scheme("https").host(host.trim()).port(port).addPathSegments("v1/control").build()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty())
                chain[0].checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) throw java.security.cert.CertificateException("Pairing certificate changed")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
            // The full certificate pin authenticates the host, including discovered IP addresses.
            .hostnameVerifier { _, _ -> true }.followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }
    fun request(value: JSONObject): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .post(value.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) error("Authentication rejected; replace desktop pairing")
            val body = response.body?.string() ?: error("Empty desktop response")
            require(body.length < 8_000_000) { "Desktop response too large" }
            val result = JSONObject(body)
            if (!response.isSuccessful) error(result.optString("error", "Desktop unavailable"))
            return result
        }
    }
    companion object {
        fun discover(): List<String> {
            val found = mutableSetOf<String>()
            val nonce = "IZIS_DISCOVER_1 " + UUID.randomUUID().toString()
            DatagramSocket().use { socket ->
                socket.broadcast = true; socket.soTimeout = 1500
                val data = nonce.toByteArray()
                socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 8767))
                val deadline = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(ByteArray(128), 128)
                    try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { break }
                    if (String(packet.data, 0, packet.length) == "$nonce 8767") found.add(packet.address.hostAddress ?: continue)
                }
            }
            return found.toList()
        }
    }
}
