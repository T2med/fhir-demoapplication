import deviceflow.DeviceFlowConfig
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

class DeviceFlowConfigTest {

    @Test
    fun `load reads values from classpath properties file`() {
        val config = DeviceFlowConfig.load()
        assertNotNull(config)
        assertNotNull(config.deviceAuthUrl)
        assertNotNull(config.tokenUrl)
        assertNotNull(config.clientId)
        assertNotNull(config.scope)
    }

    @Test
    fun `load returns default scope from file`() {
        val config = DeviceFlowConfig.load()
        assertEquals("t2med/aps/fhir", config.scope)
    }

    @Test
    fun `fromDeepLinkParams overrides base config with non-blank params`() {
        val params = mapOf(
            "deviceAuthUrl" to "https://example.com/device",
            "tokenUrl" to "https://example.com/token",
            "clientId" to "my-client"
        )
        val config = DeviceFlowConfig.fromDeepLinkParams(params)
        assertEquals("https://example.com/device", config.deviceAuthUrl)
        assertEquals("https://example.com/token", config.tokenUrl)
        assertEquals("my-client", config.clientId)
    }

    @Test
    fun `fromDeepLinkParams falls back to base config for missing params`() {
        val config = DeviceFlowConfig.fromDeepLinkParams(emptyMap())
        val base = DeviceFlowConfig.load()
        assertEquals(base.deviceAuthUrl, config.deviceAuthUrl)
        assertEquals(base.tokenUrl, config.tokenUrl)
        assertEquals(base.clientId, config.clientId)
        assertEquals(base.scope, config.scope)
    }

    @Test
    fun `fromDeepLinkParams ignores blank values`() {
        val params = mapOf("deviceAuthUrl" to "  ", "tokenUrl" to "")
        val config = DeviceFlowConfig.fromDeepLinkParams(params)
        val base = DeviceFlowConfig.load()
        assertEquals(base.deviceAuthUrl, config.deviceAuthUrl)
        assertEquals(base.tokenUrl, config.tokenUrl)
    }

    @Test
    fun `fromDeepLinkParams never populates clientSecret`() {
        val fromParams = DeviceFlowConfig.fromDeepLinkParams(mapOf("clientId" to "id"))
        assertEquals("", fromParams.clientSecret)
    }

    @Test
    fun `fromDeepLinkParams derives auth URLs from fhirBasisUrl`() {
        val params = mapOf("fhirBasisUrl" to "https://10.42.12.83:16567/aps/fhir/api")
        val config = DeviceFlowConfig.fromDeepLinkParams(params)
        assertEquals("https://10.42.12.83:16596/oauth2/device_authorization", config.deviceAuthUrl)
        assertEquals("https://10.42.12.83:16596/oauth2/token", config.tokenUrl)
    }

    @Test
    fun `fromDeepLinkParams explicit deviceAuthUrl takes precedence over fhirBasisUrl`() {
        val params = mapOf(
            "fhirBasisUrl" to "https://10.42.12.83:16567/aps/fhir/api",
            "deviceAuthUrl" to "https://other-server/device_authorization"
        )
        val config = DeviceFlowConfig.fromDeepLinkParams(params)
        assertEquals("https://other-server/device_authorization", config.deviceAuthUrl)
        // tokenUrl still derived from fhirBasisUrl since not explicitly set
        assertEquals("https://10.42.12.83:16596/oauth2/token", config.tokenUrl)
    }
}
