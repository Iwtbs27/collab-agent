package ai.opencyvis.remoteim

import ai.opencyvis.remoteim.feishu.FeishuRegistrationApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class FeishuRegistrationApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: FeishuRegistrationApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = FeishuRegistrationApi(server.url("/oauth/v1/app/registration").toString())
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `init returns nonce on success`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"status":0,"nonce":"test-nonce-123"}""")
            .setHeader("Content-Type", "application/json"))

        val result = api.init()

        assert(result is FeishuRegistrationApi.InitResult.Success)
        val success = result as FeishuRegistrationApi.InitResult.Success
        assert(success.nonce == "test-nonce-123")
    }

    @Test
    fun `init returns error on non-zero status`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"status":1,"message":"bad request"}""")
            .setHeader("Content-Type", "application/json"))

        val result = api.init()

        assert(result is FeishuRegistrationApi.InitResult.Error)
    }

    @Test
    fun `begin returns device code and user code on success`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"status":0,"device_code":"dev-abc","user_code":"1234-5678","verification_uri_complete":"https://open.feishu.cn/page/launcher?user_code=1234-5678","expires_in":300}""")
            .setHeader("Content-Type", "application/json"))

        val result = api.begin("test-nonce")

        assert(result is FeishuRegistrationApi.BeginResult.Success)
        val success = result as FeishuRegistrationApi.BeginResult.Success
        assert(success.deviceCode == "dev-abc")
        assert(success.userCode == "1234-5678")
        assert(success.expiresIn == 300)
    }

    @Test
    fun `poll returns Pending when authorization_pending`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"error":"authorization_pending"}""")
            .setHeader("Content-Type", "application/json"))

        val result = api.poll("dev-abc")

        assert(result is FeishuRegistrationApi.PollResult.Pending)
    }

    @Test
    fun `poll returns Success with credentials when status is 0`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"status":0,"client_id":"cli-123","client_secret":"sec-456","open_id":"ou-789"}""")
            .setHeader("Content-Type", "application/json"))

        val result = api.poll("dev-abc")

        assert(result is FeishuRegistrationApi.PollResult.Success)
        val success = result as FeishuRegistrationApi.PollResult.Success
        assert(success.clientId == "cli-123")
        assert(success.clientSecret == "sec-456")
        assert(success.openId == "ou-789")
    }

    @Test
    fun `poll returns Error on unexpected status`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"status":5,"message":"server error"}""")
            .setHeader("Content-Type", "application/json"))

        val result = api.poll("dev-abc")

        assert(result is FeishuRegistrationApi.PollResult.Error)
    }
}
