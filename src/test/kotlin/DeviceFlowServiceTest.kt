import deviceflow.DeviceFlowConfig
import deviceflow.DeviceFlowService
import org.apache.http.HttpVersion
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.message.BasicStatusLine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceFlowServiceTest {

    private lateinit var mockHttpClient: CloseableHttpClient
    private lateinit var config: DeviceFlowConfig
    private lateinit var service: DeviceFlowService

    @BeforeEach
    fun setUp() {
        mockHttpClient = mock()
        config = DeviceFlowConfig(
            deviceAuthUrl = "https://auth.example.com/device",
            tokenUrl = "https://auth.example.com/token",
            clientId = "test-client",
            clientSecret = "test-secret",
            scope = "openid"
        )
        service = DeviceFlowService(config, mockHttpClient)
    }

    private fun makeResponse(statusCode: Int, body: String): CloseableHttpResponse {
        val response: CloseableHttpResponse = mock()
        val statusLine = BasicStatusLine(HttpVersion.HTTP_1_1, statusCode, "")
        whenever(response.statusLine).thenReturn(statusLine)
        val entity = StringEntity(body, Charsets.UTF_8)
        whenever(response.entity).thenReturn(entity)
        return response
    }

    // -------------------------------------------------------------------------
    // requestDeviceAuthorization
    // -------------------------------------------------------------------------

    @Test
    fun `requestDeviceAuthorization parses all fields correctly`() {
        val json = """{"device_code":"dev-abc","user_code":"WXYZ-1234","verification_uri":"https://example.com/activate","expires_in":600,"interval":5}"""
        val response = makeResponse(200, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        val result = service.requestDeviceAuthorization()

        assertEquals("dev-abc", result.deviceCode)
        assertEquals("WXYZ-1234", result.userCode)
        assertEquals("https://example.com/activate", result.verificationUri)
        assertEquals(600, result.expiresIn)
        assertEquals(5, result.interval)
    }

    @Test
    fun `requestDeviceAuthorization accepts verification_url as fallback`() {
        val json = """{"device_code":"d","user_code":"U","verification_url":"https://alt.example.com","expires_in":300,"interval":5}"""
        val response = makeResponse(200, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        val result = service.requestDeviceAuthorization()
        assertEquals("https://alt.example.com", result.verificationUri)
    }

    @Test
    fun `requestDeviceAuthorization uses defaults for missing optional fields`() {
        val json = """{"device_code":"d","user_code":"U","verification_uri":"https://example.com"}"""
        val response = makeResponse(200, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        val result = service.requestDeviceAuthorization()
        assertEquals(300, result.expiresIn)
        assertEquals(5, result.interval)
    }

    @Test
    fun `requestDeviceAuthorization throws on error response`() {
        val json = """{"error":"invalid_client","error_description":"Client unknown"}"""
        val response = makeResponse(400, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        assertThrows<RuntimeException> { service.requestDeviceAuthorization() }
    }

    // -------------------------------------------------------------------------
    // pollForToken
    // -------------------------------------------------------------------------

    @Test
    fun `pollForToken returns Success on 200 with access_token`() {
        val json = """{"access_token":"tok-xyz","token_type":"bearer"}"""
        val response = makeResponse(200, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        val result = service.pollForToken("dev-abc")
        assertIs<DeviceFlowService.PollResult.Success>(result)
        assertEquals("tok-xyz", result.accessToken)
    }

    @Test
    fun `pollForToken returns Pending on authorization_pending`() {
        val json = """{"error":"authorization_pending"}"""
        val response = makeResponse(400, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        assertIs<DeviceFlowService.PollResult.Pending>(service.pollForToken("dev-abc"))
    }

    @Test
    fun `pollForToken returns SlowDown on slow_down`() {
        val json = """{"error":"slow_down"}"""
        val response = makeResponse(400, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        assertIs<DeviceFlowService.PollResult.SlowDown>(service.pollForToken("dev-abc"))
    }

    @Test
    fun `pollForToken returns Error on expired_token`() {
        val json = """{"error":"expired_token","error_description":"The device code has expired"}"""
        val response = makeResponse(400, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        val result = service.pollForToken("dev-abc")
        assertIs<DeviceFlowService.PollResult.Error>(result)
        assertEquals("expired_token", result.error)
        assertEquals("The device code has expired", result.description)
    }

    @Test
    fun `pollForToken returns Error on access_denied`() {
        val json = """{"error":"access_denied"}"""
        val response = makeResponse(400, json)
        whenever(mockHttpClient.execute(any())).thenReturn(response)

        val result = service.pollForToken("dev-abc")
        assertIs<DeviceFlowService.PollResult.Error>(result)
        assertEquals("access_denied", result.error)
    }
}
