package app.avoska.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Syncthing's REST API on 127.0.0.1 for one session. Plain HTTP on loopback, which never leaves
 * the device (network_security_config allows cleartext to 127.0.0.1 only). Other apps can reach
 * the port, so the bridge turns Syncthing's GUI auth on with a password nobody knows: the
 * per-start API key sent here is the only way in.
 * Calls block, except [events]; run them on an IO thread.
 */
class RestApi(private val session: SyncthingSession, client: OkHttpClient = sharedClient) {
    private val client = client.newBuilder().readTimeout(EVENT_TIMEOUT_SECONDS + 30, TimeUnit.SECONDS).build()

    fun get(vararg segments: String, query: Map<String, String> = emptyMap()): JsonElement =
        call("GET", url(segments, query), null)

    fun put(vararg segments: String, body: JsonObject): JsonElement = call("PUT", url(segments), body.toBody())

    fun patch(vararg segments: String, body: JsonObject): JsonElement = call("PATCH", url(segments), body.toBody())

    fun post(vararg segments: String, query: Map<String, String> = emptyMap(), body: JsonObject): JsonElement =
        call("POST", url(segments, query), body.toBody())

    /**
     * Long-polls /rest/events; returns an empty array when nothing happened within the timeout.
     * Cancelling the coroutine cancels the HTTP call at once, so stopping a session never waits
     * for the poll to time out.
     */
    suspend fun events(since: Long): JsonElement {
        val url = url(
            arrayOf("rest", "events"),
            mapOf(
                "since" to since.toString(),
                "timeout" to EVENT_TIMEOUT_SECONDS.toString(),
                "events" to WATCHED_EVENTS.joinToString(","),
            ),
        )
        val call = client.newCall(request("GET", url, null))
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { read("GET", url, response) }
                        result.fold(cont::resume, cont::resumeWithException)
                    }
                },
            )
        }
    }

    private fun url(segments: Array<out String>, query: Map<String, String> = emptyMap()): HttpUrl {
        val (host, port) = session.apiAddress.split(':').let { it[0] to it[1].toInt() }
        return HttpUrl.Builder().scheme("http").host(host).port(port).apply {
            segments.forEach(::addPathSegment)
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    private fun request(method: String, url: HttpUrl, body: RequestBody?): Request =
        Request.Builder().url(url).header("X-API-Key", session.apiKey).method(method, body).build()

    private fun call(method: String, url: HttpUrl, body: RequestBody?): JsonElement =
        read(method, url, client.newCall(request(method, url, body)).execute())

    private fun read(method: String, url: HttpUrl, response: Response): JsonElement = response.use {
        val text = it.body.string()
        if (!it.isSuccessful) throw IOException("$method ${url.encodedPath}: ${it.code} ${text.trim()}")
        if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text)
    }

    private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON)

    companion object {
        const val EVENT_TIMEOUT_SECONDS = 60L
        private val JSON = "application/json".toMediaType()
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // Syncthing's API server closes idle connections after 15 s (its ReadTimeout, no
            // IdleTimeout); reusing one later fails with "unexpected end of stream".
            .connectionPool(ConnectionPool(4, 10, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false)
            .build()
    }
}
