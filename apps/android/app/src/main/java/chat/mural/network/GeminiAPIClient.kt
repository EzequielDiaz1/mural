package chat.mural.network

import chat.mural.core.SourceLink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/** Personal BYOK client: the key is sent only to Google, never a Mural server. */
class GeminiAPIClient internal constructor(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = "https://generativelanguage.googleapis.com/v1beta/".toHttpUrl(),
) : TeachingClient, LiveSessionProvider {
    constructor(credentials: CredentialStore) : this(credentials::read)

    internal fun liveRequest(): Request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
        .header("x-goog-api-key", readCredential() ?: throw APIClient.APIException.MissingKey)
        .build()

    // GeminiLiveTransport opens a WebSocket directly; SDP is intentionally unsupported.
    override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection =
        throw IOException("Gemini requires its own voice transport.")

    override suspend fun respond(instructions: String, input: String, schema: JsonObject?,
        search: Boolean, purpose: HelperPurpose?): APIResult {
        val body = requestBody(instructions, input, schema, search)
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("models/$TEXT_MODEL:generateContent").build())
            .header("x-goog-api-key", readCredential() ?: throw APIClient.APIException.MissingKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Never propagate request URLs, headers or raw provider error bodies.
                    if (continuation.isActive) continuation.resumeWithException(IOException("Gemini: connection failed."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw APIClient.APIException.Http(it.code)
                            val source = it.body?.source() ?: throw APIClient.APIException.InvalidResponse
                            val buffer = Buffer()
                            while (buffer.size <= MAX_BYTES) {
                                if (source.read(buffer, minOf(8192L, MAX_BYTES + 1 - buffer.size)) == -1L) break
                            }
                            if (buffer.size > MAX_BYTES) throw APIClient.APIException.InvalidResponse
                            decode(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is APIClient.APIException) e else APIClient.APIException.InvalidResponse)
                    }
                }
            })
        }
    }

    companion object {
        const val LIVE_MODEL = "gemini-3.8-live"
        const val TEXT_MODEL = "gemini-2.5-flash"
        private const val MAX_BYTES = 1_048_576L
        private fun defaultClient() = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).build()

        internal fun requestBody(instructions: String, input: String, schema: JsonObject?, search: Boolean) = buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", instructions) }) }) })
            put("contents", buildJsonArray { add(buildJsonObject {
                put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", input) }) })
            }) })
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", 4096)
                put("thinkingConfig", buildJsonObject { put("thinkingBudget", 0) })
                if (schema != null) {
                    put("responseMimeType", "application/json")
                    put("responseJsonSchema", schema)
                }
            })
            if (search) put("tools", buildJsonArray { add(buildJsonObject { put("googleSearch", buildJsonObject {}) }) })
        }

        internal fun decode(response: JsonObject): APIResult {
            val candidate = (response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw APIClient.APIException.Refused
            if (candidate["finishReason"]?.jsonPrimitive?.content != "STOP") throw APIClient.APIException.Incomplete
            val text = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.takeUnless { part -> part["thought"] == JsonPrimitive(true) }
                    ?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
            if (text.isBlank()) throw APIClient.APIException.InvalidResponse
            val grounding = candidate["groundingMetadata"] as? JsonObject
            val sources = (grounding?.get("groundingChunks") as? JsonArray).orEmpty().mapNotNull {
                val web = (it as? JsonObject)?.get("web") as? JsonObject ?: return@mapNotNull null
                val url = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SourceLink(web["title"]?.jsonPrimitive?.contentOrNull ?: url, url).takeIf { link -> link.safeUrl() != null }
            }
            val usage = response["usageMetadata"] as? JsonObject
            return APIResult(text, sources, APIUsage(
                usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0,
                usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0,
                (grounding?.get("webSearchQueries") as? JsonArray)?.size ?: 0))
        }
    }
}
