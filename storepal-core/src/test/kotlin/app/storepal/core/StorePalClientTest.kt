package app.storepal.core

import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StorePalClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: StorePalClient
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = StorePalClient("sp_live_test", server.url("/").toString()) { "zh-Hant-TW,en-US" }
    }
    @After fun teardown() { server.shutdown() }
    private fun json(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }
    private val release = """{"data":{"id":"r1","version":"1.2.0","content":"# Hello","published_at":"2026-10-25","future_field":true},"error":null}"""
    private val created = """{"id":"f1","conversation_token":"secret-token","category":"bug","created_at":"2026-10-25"}"""

    @Test fun `sends app authorization exact version and preferred languages`() = runBlocking {
        json(release)
        assertEquals("1.2.0", client.getReleaseNote("1.2.0")?.version)
        val request = server.takeRequest()
        assertEquals("/api/v1/releases?version=1.2.0", request.path)
        assertEquals("Bearer sp_live_test", request.getHeader("Authorization"))
        assertEquals("zh-Hant-TW,en-US", request.getHeader("Accept-Language"))
    }
    @Test fun `retains base proxy path`() = runBlocking {
        json(release)
        StorePalClient("sp_live_test", server.url("/proxy/").toString()).getReleaseNote("1.2.0")
        assertEquals("/proxy/api/v1/releases?version=1.2.0", server.takeRequest().path)
    }
    @Test fun `preserves plus email alias and query characters`() = runBlocking {
        json("""{"data":{"conversations":[],"total":0,"page":1,"per_page":20},"error":null}""")
        val email = "reader+android@example.com"
        client.listConversations(email)
        assertEquals(email, server.takeRequest().requestUrl!!.queryParameter("email"))
    }
    @Test fun `encodes token as one path segment`() = runBlocking {
        json("""{"data":null,"error":{"code":"NOT_FOUND","message":"Missing"}}""", 404)
        assertFailsWith<StorePalException> { client.getConversation("token/with?query#fragment") }
        val path = server.takeRequest().requestUrl!!
        assertEquals("token/with?query#fragment", path.pathSegments.last())
        assertNull(path.query)
    }
    @Test fun `submits server schema and attachment ids`() = runBlocking {
        json("""{"data":$created,"error":null}""", 201)
        val result = client.submitFeedback(" Alex ", "a@example.com", FeedbackCategory.BUG, " Something broke ",
            mapOf("os_version" to "Android 15"), listOf("a1"))
        assertEquals("secret-token", result.conversationToken)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertContains(body, "\"attachment_ids\":[\"a1\"]")
        assertContains(body, "\"os_version\":\"Android 15\"")
        assertContains(body, "\"message\":\"Something broke\"")
    }
    @Test fun `partial bind is accepted feedback with a warning`() = runBlocking {
        json("""{"data":$created,"error":{"code":"PARTIAL_BIND","message":"Some files failed"}}""", 207)
        assertTrue(client.submitFeedback("Alex", "a@example.com", FeedbackCategory.BUG, "Message").partialAttachmentFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun `partial bind is accepted reply with a warning`() = runBlocking {
        json("""{"data":{"id":"r1","sender_type":"user","message":"Reply","created_at":"today"},"error":{"code":"PARTIAL_BIND","message":"Some files failed"}}""", 207)
        assertTrue(client.postReply("token", "Reply").partialAttachmentFailure)
        assertEquals("/api/v1/conversations/token/reply", server.takeRequest().path)
    }
    @Test fun `uploads a valid multipart file`() = runBlocking {
        json("""{"data":{"attachment_id":"a1","file_name":"trace.log","mime_type":"text/plain","size_bytes":5},"error":null}""", 201)
        assertEquals("a1", client.uploadAttachment("hello".toByteArray(), "trace.log", "text/plain").attachmentId)
        val request = server.takeRequest()
        assertContains(request.getHeader("Content-Type")!!, "multipart/form-data; boundary=")
        assertContains(request.body.readUtf8(), "filename=\"trace.log\"")
    }
    @Test fun `missing release returns null`() = runBlocking {
        json("""{"data":null,"error":{"code":"NOT_FOUND","message":"Missing"}}""", 404)
        assertNull(client.getReleaseNote("1.2.0"))
    }
    @Test fun `authorization errors are not missing releases`() = runBlocking {
        json("""{"data":null,"error":{"code":"INVALID_API_KEY","message":"Invalid key"}}""", 401)
        assertEquals(401, assertFailsWith<StorePalException> { client.getReleaseNote("1.2.0") }.statusCode)
    }
    @Test fun `cooldown remains machine readable`() = runBlocking {
        json("""{"data":null,"error":{"code":"COOLDOWN","message":"Wait"}}""", 400)
        assertEquals("COOLDOWN", assertFailsWith<StorePalException> { client.postReply("token", "Hi") }.code)
    }
    @Test fun `rate limit includes retry delay`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
            .setBody("""{"data":null,"error":{"code":"RATE_LIMITED","message":"Wait"}}"""))
        assertEquals(60, assertFailsWith<StorePalException> { client.getUnreadCount("a@example.com") }.retryAfterSeconds)
    }
    @Test fun `malformed responses are typed errors`() = runBlocking {
        json("<html>unavailable</html>", 502)
        assertEquals("HTTP_502", assertFailsWith<StorePalException> { client.getReleaseNote("1.2.0") }.code)
    }
    @Test fun `missing data is rejected`() = runBlocking {
        json("""{"data":null,"error":null}""")
        assertEquals("NO_DATA", assertFailsWith<StorePalException> { client.getReleaseNote("1.2.0") }.code)
    }
    @Test fun `valid JSON with an invalid data schema is a typed error`() = runBlocking {
        json("""{"data":{"wrong":"shape"},"error":null}""")
        assertEquals("INVALID_RESPONSE", assertFailsWith<StorePalException> { client.getReleaseNote("1.2.0") }.code)
    }
    @Test fun `long attachment names keep a usable extension`() = runBlocking {
        json("""{"data":{"attachment_id":"a1","file_name":"trace.log","mime_type":"text/plain","size_bytes":5},"error":null}""", 201)
        client.uploadAttachment("hello".toByteArray(), "x".repeat(300) + ".log", "text/plain")
        val body = server.takeRequest().body.readUtf8()
        assertContains(body, "filename=\"${"x".repeat(110)}.log\"")
    }
    @Test fun `redirect is not followed with app credentials`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        assertFailsWith<StorePalException> { client.getReleaseNote("1.2.0") }
        assertEquals(1, server.requestCount)
    }
    @Test fun `cancellation cancels the request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = launch { client.getReleaseNote("1.2.0") }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        withTimeout(2000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
    }
    @Test fun `rejects management tokens and insecure remote endpoints`() {
        assertFailsWith<IllegalArgumentException> { StorePalClient("sp_user_secret") }
        assertFailsWith<IllegalArgumentException> { StorePalClient("sp_live_test", "http://example.com") }
        assertFailsWith<IllegalArgumentException> { StorePalClient("sp_live_test", "https://name:secret@example.com") }
    }
    @Test fun `validates submissions before sending`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { client.submitFeedback("", "a@example.com", FeedbackCategory.BUG, "Hi") }
        assertFailsWith<IllegalArgumentException> { client.submitFeedback("Alex", "invalid", FeedbackCategory.BUG, "Hi") }
        assertFailsWith<IllegalArgumentException> { client.postReply("token", "x".repeat(5001)) }
        assertFailsWith<IllegalArgumentException> { client.postReply("token", "Hi", listOf("a", "a")) }
        assertEquals(0, server.requestCount)
    }
    @Test fun `conversation handles nullable metadata and absent attachments`() = runBlocking {
        json("""{"data":{"feedback":{"id":"f1","name":"Alex","email":"a@example.com","category":"other","message":"Hello","metadata":null,"created_at":"today"},"replies":[]},"error":null}""")
        val detail = client.getConversation("token")
        assertNull(detail.feedback.metadata)
        assertNull(detail.feedback.attachments)
    }

    @Test fun `private inbox sends capabilities in body and never marks threads seen`() = runBlocking {
        val token = "00000000-0000-4000-8000-000000000001"
        val item = """{"id":"f1","conversation_token":"$token","category":"bug","message":"Private feedback","reply_count":1,"has_unread_reply":true,"created_at":"2026-10-25"}"""
        json("""{"data":{"conversations":[$item],"total":1,"page":1,"per_page":100},"error":null}""")
        val result = client.listConversations(listOf(token))
        assertEquals(1, result.total)
        assertTrue(result.conversations.single().hasUnreadReply)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/conversations/lookup", request.path)
        assertEquals("""{"tokens":["$token"]}""", request.body.readUtf8())
        assertNull(request.requestUrl!!.query)
        assertEquals(1, server.requestCount)
    }

    @Test fun `new inbox batches and caches pages without trusting unrequested tokens`() = runBlocking {
        val tokens = (1..101).map { java.util.UUID(0, it.toLong()).toString() }
        fun page(batch: List<String>) = """{"data":{"conversations":[${(batch + "ffffffff-ffff-4fff-8fff-ffffffffffff").joinToString(",") { token ->
            """{"id":"$token","conversation_token":"$token","category":"bug","message":"Saved","reply_count":1,"has_unread_reply":true,"created_at":"2026-10-25"}"""
        }}],"total":${batch.size},"page":1,"per_page":100},"error":null}"""
        tokens.chunked(100).forEach { json(page(it)) }
        assertEquals(101, client.listConversations(tokens).total)
        assertEquals(20, client.listConversations(tokens, 2).conversations.size)
        assertEquals(2, server.requestCount)
        tokens.chunked(100).forEach { json(page(it)) }
        assertEquals(101, client.getUnreadCount(tokens))
        assertEquals(4, server.requestCount)
    }

    @Test fun `empty private inbox makes no request and failures never fall back to email`() = runBlocking {
        assertEquals(0, client.listConversations(emptyList()).total)
        assertEquals(0, server.requestCount)
        assertFailsWith<IllegalArgumentException> { client.listConversations(listOf("invalid")) }
        json("""{"data":null,"error":{"code":"NOT_FOUND","message":"Missing"}}""", 404)
        assertEquals("NOT_FOUND", assertFailsWith<StorePalException> {
            client.listConversations(listOf("00000000-0000-4000-8000-000000000001"))
        }.code)
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/conversations/lookup", server.takeRequest().path)
    }

    @Test fun `hosted capability inbox release verification`() = runBlocking {
        val path = System.getenv("STOREPAL_LIVE_QA_STATE")
        assumeNotNull(path)
        val state = Json.parseToJsonElement(java.io.File(path!!).readText()).jsonObject
        val live = StorePalClient(state.getValue("app_key").jsonPrimitive.content)
        val tokens = listOf(state.getValue("conversation_token").jsonPrimitive.content)
        assertEquals(1, live.listConversations(tokens).total)
        assertEquals(1, live.getUnreadCount(tokens))
    }
}
