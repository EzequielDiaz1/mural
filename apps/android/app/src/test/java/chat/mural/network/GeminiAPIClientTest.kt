package chat.mural.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GeminiAPIClientTest {
    @Test fun `keys cannot be used with the wrong provider`() {
        val google = "AIza" + "x".repeat(35)
        val openai = "sk-" + "x".repeat(40)
        assertTrue(AIProvider.GEMINI.acceptsKey(google))
        assertFalse(AIProvider.OPENAI.acceptsKey(google))
        assertFalse(AIProvider.GEMINI.acceptsKey(openai))
        assertFalse(AIProvider.GEMINI.acceptsKey(google + "\n"))
    }

    @Test fun `structured teaching sends Google auth without putting credentials in URL or body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"ok\":true}"}]}}],"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":5}}"""))
            server.start()
            val key = "AIza" + "x".repeat(35)
            val api = GeminiAPIClient({ key }, OkHttpClient(), server.url("/v1beta/"))
            val schema = buildJsonObject { put("type", "object") }
            val result = api.respond("Teach English", "Hello", schema, false, HelperPurpose.ASSESSMENT)
            assertEquals("{\"ok\":true}", result.text)
            assertEquals(20, result.usage.input)
            val request = server.takeRequest()
            assertEquals(key, request.getHeader("x-goog-api-key"))
            assertNull(request.getHeader("Authorization"))
            assertFalse(request.path!!.contains(key))
            val raw = request.body.readUtf8()
            assertFalse(raw.contains(key))
            val body = Json.parseToJsonElement(raw).jsonObject
            assertEquals(schema, body["generationConfig"]!!.jsonObject["responseJsonSchema"])
            assertEquals(JsonPrimitive("application/json"), body["generationConfig"]!!.jsonObject["responseMimeType"])
        }
    }

    @Test fun `provider error bodies cannot leak secrets through exceptions`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("private provider payload"))
            server.start()
            try {
                GeminiAPIClient({ "AIza" + "x".repeat(35) }, OkHttpClient(), server.url("/"))
                    .respond("test", "test", null, false, null)
                fail("Expected rate limit")
            } catch (e: APIClient.APIException.Http) {
                assertEquals(429, e.status)
                assertFalse(e.toString().contains("private provider payload"))
            }
        }
    }

    @Test fun `truncated teaching JSON is rejected`() {
        try {
            GeminiAPIClient.decode(Json.parseToJsonElement("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"partial"}]}}]}""").jsonObject)
            fail("Expected incomplete response")
        } catch (_: APIClient.APIException.Incomplete) { }
    }

    @Test fun `thought text is not shown and grounding sources are decoded`() {
        val result = GeminiAPIClient.decode(Json.parseToJsonElement("""{
          "candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"hidden"},{"text":"Hello"}]},
          "groundingMetadata":{"groundingChunks":[{"web":{"title":"Source","uri":"https://example.com"}}],"webSearchQueries":["topic"]}}]}
        """).jsonObject)
        assertEquals("Hello", result.text)
        assertEquals(1, result.sources.size)
        assertEquals(1, result.usage.searches)
    }

    @Test fun `voice setup enables both transcripts and audio responses`() {
        val setup = GeminiLiveTransport.setup("Practice English")["setup"]!!.jsonObject
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
        assertEquals(JsonPrimitive("AUDIO"), setup["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray.single())
        assertTrue(setup["model"]!!.jsonPrimitive.content.startsWith("models/gemini-"))
    }
}
