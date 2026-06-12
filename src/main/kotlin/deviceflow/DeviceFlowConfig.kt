package deviceflow

import constants.AppConstants
import java.util.Properties

data class DeviceFlowConfig(
    val deviceAuthUrl: String = "",
    val tokenUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val scope: String = "openid profile"
) {
    companion object {
        fun load(): DeviceFlowConfig {
            val props = Properties()
            DeviceFlowConfig::class.java.getResourceAsStream("/device-flow.properties")?.use {
                props.load(it)
            }
            val userFile = java.io.File(System.getProperty("user.home"), ".t2demo/device-flow.properties")
            if (userFile.exists()) {
                userFile.inputStream().use { props.load(it) }
            }
            return DeviceFlowConfig(
                deviceAuthUrl = props.getProperty("device.auth.url", ""),
                tokenUrl = props.getProperty("token.url", ""),
                clientId = props.getProperty("client.id", ""),
                scope = props.getProperty("scope", "openid profile")
            )
        }

        /**
         * Leitet die Auth-Server-URLs direkt aus einer FHIR-Basis-URL ab (gleicher Host, Port 16596).
         * Wird beim automatischen Reconnect verwendet, wenn keine Deep-Link-Parameter vorliegen.
         */
        fun fromFhirUrl(fhirUrl: String, clientSecret: String = ""): DeviceFlowConfig {
            val base = load()
            val authBase = runCatching {
                val uri = java.net.URI(fhirUrl)
                "${uri.scheme}://${uri.host}:${AppConstants.AUTH_SERVER_PORT}"
            }.getOrNull()
            return DeviceFlowConfig(
                deviceAuthUrl = authBase?.let { "$it/oauth2/device_authorization" } ?: base.deviceAuthUrl,
                tokenUrl = authBase?.let { "$it/oauth2/token" } ?: base.tokenUrl,
                clientId = base.clientId,
                clientSecret = clientSecret,
                scope = base.scope
            )
        }

        fun fromDeepLinkParams(params: Map<String, String>): DeviceFlowConfig {
            val base = load()
            // Derive auth server base URL from fhirBasisUrl (same host, auth port 16596).
            // APS only ever sends kontextId, fhirBasisUrl and oAuthToken in the Deep Link —
            // no dedicated OAuth endpoint parameters are transmitted.
            val derivedAuthBase = params["fhirBasisUrl"]
                ?.takeIf { it.isNotBlank() }
                ?.let { fhirUrl ->
                    runCatching {
                        val uri = java.net.URI(fhirUrl)
                        "${uri.scheme}://${uri.host}:${AppConstants.AUTH_SERVER_PORT}"
                    }.getOrNull()
                }
            return DeviceFlowConfig(
                deviceAuthUrl = derivedAuthBase?.let { "$it/oauth2/device_authorization" }
                    ?: base.deviceAuthUrl,
                tokenUrl = derivedAuthBase?.let { "$it/oauth2/token" }
                    ?: base.tokenUrl,
                clientId = base.clientId,
                scope = base.scope
            )
        }
    }
}
