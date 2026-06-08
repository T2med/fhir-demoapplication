package deviceflow

import javax.swing.SwingUtilities

class DeviceFlowPoller(
    private val service: DeviceFlowService,
    private val deviceCode: String,
    private var intervalSeconds: Int,
    private val onSuccess: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Thread("DeviceFlowPoller") {

    @Volatile
    var cancelled = false

    init {
        isDaemon = true
    }

    override fun run() {
        while (!cancelled) {
            try {
                Thread.sleep(intervalSeconds * 1000L)
            } catch (e: InterruptedException) {
                return
            }
            if (cancelled) return

            try {
                when (val result = service.pollForToken(deviceCode)) {
                    is DeviceFlowService.PollResult.Success -> {
                        SwingUtilities.invokeLater { onSuccess(result.accessToken) }
                        return
                    }
                    is DeviceFlowService.PollResult.SlowDown -> {
                        intervalSeconds += 5
                        SwingUtilities.invokeLater {
                            onStatusUpdate("Server bittet um langsameres Polling (alle ${intervalSeconds}s)...")
                        }
                    }
                    is DeviceFlowService.PollResult.Pending -> { /* weiter warten */ }
                    is DeviceFlowService.PollResult.Error -> {
                        val msg = "${result.error}${result.description?.let { ": $it" } ?: ""}"
                        SwingUtilities.invokeLater { onError(msg) }
                        return
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { onError("Netzwerkfehler: ${e.message}") }
                return
            }
        }
    }

    fun cancel() {
        cancelled = true
        interrupt()
    }
}
