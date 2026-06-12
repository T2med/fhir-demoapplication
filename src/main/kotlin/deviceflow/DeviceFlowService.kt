package deviceflow

import TrustAllCerts
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.socket.ConnectionSocketFactory
import org.apache.http.conn.socket.PlainConnectionSocketFactory
import org.apache.http.message.BasicNameValuePair
import java.util.Base64

class DeviceFlowService(
    private val config: DeviceFlowConfig,
    private val httpClient: CloseableHttpClient = buildHttpClient(config.deviceAuthUrl)
) {
    private val mapper = ObjectMapper()

    data class DeviceAuthResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int
    )

    sealed class PollResult {
        data class Success(val accessToken: String) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        data class Error(val error: String, val description: String?) : PollResult()
    }

    private fun basicAuthHeader(): String {
        val credentials = "${config.clientId}:${config.clientSecret}"
        return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
    }

    fun requestDeviceAuthorization(): DeviceAuthResponse {
        val post = HttpPost(config.deviceAuthUrl)
        post.setHeader("Authorization", basicAuthHeader())
        val params = listOf(
            BasicNameValuePair("client_id", config.clientId),
            BasicNameValuePair("scope", config.scope)
        )
        post.entity = UrlEncodedFormEntity(params, Charsets.UTF_8)

        httpClient.execute(post).use { response ->
            val body = response.entity.content.readBytes().toString(Charsets.UTF_8)
            val json = mapper.readTree(body)

            if (response.statusLine.statusCode != 200) {
                val error = json.get("error")?.asText() ?: "unknown_error"
                val desc = json.get("error_description")?.asText()
                throw RuntimeException("Device authorization failed: $error${desc?.let { " — $it" } ?: ""}")
            }

            return DeviceAuthResponse(
                deviceCode = json.get("device_code")?.asText()
                    ?: throw RuntimeException("Feld 'device_code' fehlt in der Antwort"),
                userCode = json.get("user_code")?.asText()
                    ?: throw RuntimeException("Feld 'user_code' fehlt in der Antwort"),
                verificationUri = (json.get("verification_uri") ?: json.get("verification_url"))?.asText()
                    ?: throw RuntimeException("Feld 'verification_uri' fehlt in der Antwort"),
                expiresIn = json.get("expires_in")?.asInt() ?: 300,
                interval = json.get("interval")?.asInt() ?: 5
            )
        }
    }

    companion object {
        fun buildHttpClient(url: String): CloseableHttpClient {
            if (!url.startsWith("https://")) return HttpClients.createDefault()
            val sslContext = TrustAllCerts.getDynamicSslContext()
            val sslSocketFactory = SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE)
            val registry = RegistryBuilder.create<ConnectionSocketFactory>()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", sslSocketFactory)
                .build()
            val connManager = PoolingHttpClientConnectionManager(registry)
            return HttpClients.custom().setConnectionManager(connManager).build()
        }
    }

    fun pollForToken(deviceCode: String): PollResult {
        val post = HttpPost(config.tokenUrl)
        post.setHeader("Authorization", basicAuthHeader())
        val params = listOf(
            BasicNameValuePair("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            BasicNameValuePair("device_code", deviceCode),
            BasicNameValuePair("client_id", config.clientId)
        )
        post.entity = UrlEncodedFormEntity(params, Charsets.UTF_8)

        httpClient.execute(post).use { response ->
            val body = response.entity.content.readBytes().toString(Charsets.UTF_8)
            val json = mapper.readTree(body)

            if (response.statusLine.statusCode == 200 && json.has("access_token")) {
                return PollResult.Success(json.get("access_token").asText())
            }

            val error = json.get("error")?.asText() ?: "unknown_error"
            val desc = json.get("error_description")?.asText()
            return when (error) {
                "authorization_pending" -> PollResult.Pending
                "slow_down" -> PollResult.SlowDown
                else -> PollResult.Error(error, desc)
            }
        }
    }
}
