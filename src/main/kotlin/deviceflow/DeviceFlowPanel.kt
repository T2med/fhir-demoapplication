package deviceflow

import java.awt.*
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.*

class DeviceFlowPanel(
    private val initialConfig: DeviceFlowConfig,
    private val initialFhirUrl: String = "",
    private val initialKontextId: String = "",
    private val onSuccess: (fhirUrl: String, kontextId: String, accessToken: String, refreshToken: String?, clientSecret: String) -> Unit
) : JPanel(BorderLayout()) {

    private val cards = JPanel(CardLayout())
    private val cardLayout get() = cards.layout as CardLayout

    // Phase 1 – Konfiguration
    private val tfDeviceAuthUrl = JTextField(initialConfig.deviceAuthUrl, 36)
    private val tfTokenUrl = JTextField(initialConfig.tokenUrl, 36)
    private val tfClientId = JTextField(initialConfig.clientId, 36)
    private val pfClientSecret = JPasswordField(36)
    private val tfScope = JTextField(initialConfig.scope, 36)
    private val btnStart = JButton("Device Flow starten")

    // Phase 2 – Warten
    private val lblUserCode = JLabel("", JLabel.CENTER)
    private val lblVerificationUri = JLabel("", JLabel.CENTER)
    private val lblCountdown = JLabel("", JLabel.CENTER)
    private val lblStatus = JLabel("Warte auf Bestätigung...", JLabel.CENTER)
    private val btnCopyCode = JButton("Code kopieren")
    private val btnOpenBrowser = JButton("Im Browser öffnen")
    private val btnCancel = JButton("Abbrechen")
    private var countdownTimer: Timer? = null
    private var poller: DeviceFlowPoller? = null
    private var verificationUriValue = ""

    // Phase 3 – Verbinden
    private val tfFhirUrl = JTextField(initialFhirUrl, 36)
    private val tfKontextId = JTextField(initialKontextId, 36)
    private val btnConnect = JButton("Verbinden")
    private var receivedToken = ""
    private var receivedRefreshToken: String? = null

    init {
        cards.add(buildConfigPanel(), "config")
        cards.add(buildWaitingPanel(), "waiting")
        cards.add(buildConnectPanel(), "connect")
        add(cards, BorderLayout.CENTER)
        setupActions()
    }

    // -------------------------------------------------------------------------
    // Panel-Builder
    // -------------------------------------------------------------------------

    private fun buildConfigPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(15, 20, 15, 20)

        val title = JLabel("Standalone-Anmeldung via OAuth Device Flow")
        title.font = Font("Arial", Font.BOLD, 14)
        title.alignmentX = LEFT_ALIGNMENT
        panel.add(title)
        panel.add(Box.createVerticalStrut(15))

        panel.add(formRow("Device Auth URL:", tfDeviceAuthUrl))
        panel.add(Box.createVerticalStrut(6))
        panel.add(formRow("Token URL:", tfTokenUrl))
        panel.add(Box.createVerticalStrut(6))
        panel.add(formRow("Client ID:", tfClientId))
        panel.add(Box.createVerticalStrut(6))
        panel.add(formRow("Client Secret:", pfClientSecret))
        panel.add(Box.createVerticalStrut(6))
        panel.add(formRow("Scope:", tfScope))
        panel.add(Box.createVerticalStrut(12))

        val hint = JLabel("<html><i>Client Secret aus der APS-Drittanbieter-Einrichtung per Einfügen (Strg+V / Cmd+V) übernehmen.</i></html>")
        hint.foreground = Color.GRAY
        hint.alignmentX = LEFT_ALIGNMENT
        panel.add(hint)
        panel.add(Box.createVerticalStrut(15))

        btnStart.alignmentX = LEFT_ALIGNMENT
        panel.add(btnStart)

        return panel
    }

    private fun buildWaitingPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

        val title = JLabel("Bitte im Browser autorisieren")
        title.font = Font("Arial", Font.BOLD, 14)
        title.alignmentX = CENTER_ALIGNMENT
        panel.add(title)
        panel.add(Box.createVerticalStrut(20))

        val codeBorder = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(180, 180, 180)),
            BorderFactory.createEmptyBorder(10, 20, 10, 20)
        )
        lblUserCode.font = Font("Monospaced", Font.BOLD, 32)
        lblUserCode.border = codeBorder
        lblUserCode.alignmentX = CENTER_ALIGNMENT
        panel.add(lblUserCode)
        panel.add(Box.createVerticalStrut(10))

        btnCopyCode.alignmentX = CENTER_ALIGNMENT
        panel.add(btnCopyCode)
        panel.add(Box.createVerticalStrut(18))

        val urlHint = JLabel("URL zum Öffnen:", JLabel.CENTER)
        urlHint.alignmentX = CENTER_ALIGNMENT
        panel.add(urlHint)
        panel.add(Box.createVerticalStrut(4))

        lblVerificationUri.foreground = Color(0, 80, 200)
        lblVerificationUri.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        lblVerificationUri.alignmentX = CENTER_ALIGNMENT
        panel.add(lblVerificationUri)
        panel.add(Box.createVerticalStrut(6))

        btnOpenBrowser.alignmentX = CENTER_ALIGNMENT
        panel.add(btnOpenBrowser)
        panel.add(Box.createVerticalStrut(18))

        lblCountdown.font = Font("Arial", Font.PLAIN, 12)
        lblCountdown.foreground = Color.GRAY
        lblCountdown.alignmentX = CENTER_ALIGNMENT
        panel.add(lblCountdown)
        panel.add(Box.createVerticalStrut(6))

        lblStatus.alignmentX = CENTER_ALIGNMENT
        panel.add(lblStatus)
        panel.add(Box.createVerticalStrut(15))

        btnCancel.alignmentX = CENTER_ALIGNMENT
        panel.add(btnCancel)

        return panel
    }

    private fun buildConnectPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(15, 20, 15, 20)

        val successLabel = JLabel("Authentifizierung erfolgreich!")
        successLabel.font = Font("Arial", Font.BOLD, 14)
        successLabel.foreground = Color(0, 130, 0)
        successLabel.alignmentX = LEFT_ALIGNMENT
        panel.add(successLabel)
        panel.add(Box.createVerticalStrut(20))

        val hint = JLabel("Bitte FHIR-Verbindungsdaten eingeben:")
        hint.alignmentX = LEFT_ALIGNMENT
        panel.add(hint)
        panel.add(Box.createVerticalStrut(10))

        panel.add(formRow("FHIR Basis-URL:", tfFhirUrl))
        panel.add(Box.createVerticalStrut(6))
        panel.add(formRow("Kontext-ID:", tfKontextId))
        panel.add(Box.createVerticalStrut(15))

        btnConnect.alignmentX = LEFT_ALIGNMENT
        panel.add(btnConnect)

        return panel
    }

    private fun formRow(labelText: String, field: JComponent): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.alignmentX = LEFT_ALIGNMENT
        val lbl = JLabel(labelText)
        lbl.preferredSize = Dimension(140, 26)
        row.add(lbl)
        row.add(field)
        return row
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private fun setupActions() {
        btnStart.addActionListener { startDeviceFlow() }

        btnCopyCode.addActionListener {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(lblUserCode.text.trim()), null)
        }

        btnOpenBrowser.addActionListener { openVerificationUri() }

        lblVerificationUri.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = openVerificationUri()
        })

        btnCancel.addActionListener {
            stopPolling()
            cardLayout.show(cards, "config")
        }

        btnConnect.addActionListener {
            val fhirUrl = tfFhirUrl.text.trim()
            val kontextId = tfKontextId.text.trim()
            when {
                fhirUrl.isBlank() -> JOptionPane.showMessageDialog(
                    this, "Bitte FHIR Basis-URL eingeben.", "Eingabe fehlt", JOptionPane.WARNING_MESSAGE
                )
                kontextId.isBlank() -> JOptionPane.showMessageDialog(
                    this, "Bitte Kontext-ID eingeben.", "Eingabe fehlt", JOptionPane.WARNING_MESSAGE
                )
                else -> onSuccess(fhirUrl, kontextId, receivedToken, receivedRefreshToken, String(pfClientSecret.password))
            }
        }
    }

    private fun openVerificationUri() {
        if (verificationUriValue.isNotBlank()) {
            try {
                Desktop.getDesktop().browse(URI(verificationUriValue))
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this, "Browser konnte nicht geöffnet werden:\n${e.message}",
                    "Fehler", JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Device Flow Ablauf
    // -------------------------------------------------------------------------

    private fun startDeviceFlow() {
        val config = DeviceFlowConfig(
            deviceAuthUrl = tfDeviceAuthUrl.text.trim(),
            tokenUrl = tfTokenUrl.text.trim(),
            clientId = tfClientId.text.trim(),
            clientSecret = String(pfClientSecret.password),
            scope = tfScope.text.trim()
        )

        if (config.deviceAuthUrl.isBlank() || config.tokenUrl.isBlank() || config.clientId.isBlank()) {
            JOptionPane.showMessageDialog(
                this, "Bitte Device Auth URL, Token URL und Client ID ausfüllen.",
                "Eingabe fehlt", JOptionPane.WARNING_MESSAGE
            )
            return
        }

        btnStart.isEnabled = false
        btnStart.text = "Verbinde..."

        Thread {
            try {
                val service = DeviceFlowService(config)
                val authResponse = service.requestDeviceAuthorization()

                SwingUtilities.invokeLater {
                    verificationUriValue = authResponse.verificationUri
                    lblUserCode.text = authResponse.userCode
                    lblVerificationUri.text = "<html><u>${authResponse.verificationUri}</u></html>"
                    lblStatus.text = "Warte auf Bestätigung..."
                    lblStatus.foreground = Color.DARK_GRAY

                    startCountdown(authResponse.expiresIn)
                    cardLayout.show(cards, "waiting")

                    poller = DeviceFlowPoller(
                        service = service,
                        deviceCode = authResponse.deviceCode,
                        intervalSeconds = authResponse.interval,
                        onSuccess = { token, refreshToken -> onTokenReceived(token, refreshToken) },
                        onError = { msg -> onPollingError(msg) },
                        onStatusUpdate = { msg -> lblStatus.text = msg }
                    )
                    poller!!.start()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    btnStart.isEnabled = true
                    btnStart.text = "Device Flow starten"
                    JOptionPane.showMessageDialog(
                        this, "Fehler beim Starten des Device Flow:\n${e.message}",
                        "Fehler", JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun startCountdown(expiresIn: Int) {
        var remaining = expiresIn
        countdownTimer?.stop()
        updateCountdownLabel(remaining)
        countdownTimer = Timer(1000) {
            remaining--
            updateCountdownLabel(remaining)
            if (remaining <= 0) {
                countdownTimer?.stop()
                stopPolling()
                lblStatus.text = "Zeit abgelaufen. Bitte erneut starten."
                lblStatus.foreground = Color.RED
            }
        }
        countdownTimer!!.start()
    }

    private fun updateCountdownLabel(seconds: Int) {
        val mins = seconds / 60
        val secs = seconds % 60
        lblCountdown.text = "Verbleibende Zeit: %02d:%02d".format(mins, secs)
    }

    private fun onTokenReceived(token: String, refreshToken: String?) {
        countdownTimer?.stop()
        poller = null
        receivedToken = token
        receivedRefreshToken = refreshToken
        btnStart.isEnabled = true
        btnStart.text = "Device Flow starten"
        cardLayout.show(cards, "connect")
    }

    private fun onPollingError(msg: String) {
        countdownTimer?.stop()
        poller = null
        lblStatus.text = "Fehler: $msg"
        lblStatus.foreground = Color.RED
    }

    private fun stopPolling() {
        countdownTimer?.stop()
        poller?.cancel()
        poller = null
        btnStart.isEnabled = true
        btnStart.text = "Device Flow starten"
    }
}
