package deviceflow

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

        fun fromDeepLinkParams(params: Map<String, String>): DeviceFlowConfig {
            val base = load()
            return DeviceFlowConfig(
                deviceAuthUrl = params["deviceAuthUrl"]?.takeIf { it.isNotBlank() } ?: base.deviceAuthUrl,
                tokenUrl = params["tokenUrl"]?.takeIf { it.isNotBlank() } ?: base.tokenUrl,
                clientId = params["clientId"]?.takeIf { it.isNotBlank() } ?: base.clientId,
                scope = base.scope
            )
        }
    }
}
