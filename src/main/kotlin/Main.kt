import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.net.URI
import java.net.URLDecoder
import javax.swing.*
import javax.swing.table.DefaultTableModel
import org.hl7.fhir.r4.model.*
import ca.uhn.fhir.context.FhirContext
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.concurrent.Executors
import constants.AppConstants
import constants.FhirConstants

fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        val app = DemoApp()
        app.isVisible = true

        // Wenn die URL als Argument übergeben wurde
        if (args.isNotEmpty()) {
            app.handleUrl(args[0])
        }

        // Desktop-Integration für macOS URL Handler
        try {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler { event ->
                    SwingUtilities.invokeLater {
                        app.handleUrl(event.uri.toString())
                    }
                }
            }
        } catch (e: Exception) {
            println("Desktop-Integration nicht verfügbar: ${e.message}")
        }
    }
}

class DemoApp : JFrame("T2demo Custom URL App") {
    private val logger = LoggerFactory.getLogger(DemoApp::class.java)
    private val executor = Executors.newFixedThreadPool(2)
    private val urlLabel = JLabel("Warte auf URL-Aufruf...")
    private val protocolLabel = JLabel("-")
    private val hostLabel = JLabel("-")
    private val pathLabel = JLabel("-")
    private val tableModel = DefaultTableModel(arrayOf("Parameter", "Wert"), 0)
    private val table = JTable(tableModel)

    private var kontextId: String? = null
    private var fhirBasisUrl: String? = null
    private var oAuthToken: String? = null
    private var fhirService: FhirService? = null

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(800, 700)
        setLocationRelativeTo(null)

        val mainPanel = JPanel(BorderLayout(10, 10))
        mainPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        
        // Titelbereich
        val titleLabel = JLabel("T2demo FHIR API Demo", JLabel.CENTER)
        titleLabel.font = Font("Arial", Font.BOLD, 20)
        mainPanel.add(titleLabel, BorderLayout.NORTH)

        // Center SplitPane: URL Details oben, API Tests unten
        val centerPanel = JPanel(BorderLayout(0, 10))
        
        // Details Panel
        val detailsPanel = JPanel()
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        
        detailsPanel.add(createDetailRow("Vollständige URL:", urlLabel))
        detailsPanel.add(createDetailRow("Protokoll:", protocolLabel))
        detailsPanel.add(createDetailRow("Host:", hostLabel))
        detailsPanel.add(createDetailRow("Pfad:", pathLabel))
        
        detailsPanel.add(Box.createVerticalStrut(10))
        detailsPanel.add(JLabel("Query Parameter:").apply { font = font.deriveFont(Font.BOLD) })
        detailsPanel.add(Box.createVerticalStrut(5))
        
        table.isEnabled = false
        val scrollPane = JScrollPane(table)
        scrollPane.preferredSize = java.awt.Dimension(700, 150)
        
        val urlDetailsContainer = JPanel(BorderLayout())
        urlDetailsContainer.add(detailsPanel, BorderLayout.NORTH)
        urlDetailsContainer.add(scrollPane, BorderLayout.CENTER)
        
        // API Test Panel
        val apiTestPanel = JPanel()
        apiTestPanel.layout = BoxLayout(apiTestPanel, BoxLayout.Y_AXIS)
        apiTestPanel.border = BorderFactory.createTitledBorder("API-Tests")
        
        val buttonPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        
        val btnSearchPatient = JButton("Patient (Kontext) suchen")
        btnSearchPatient.addActionListener { testSearchPatient() }
        buttonPanel.add(btnSearchPatient)
        
        val btnCreateObs = JButton("Observation (Befund) anlegen")
        btnCreateObs.addActionListener { testCreateObservation() }
        buttonPanel.add(btnCreateObs)
        
        val btnCreateCond = JButton("Condition (Diagnose) anlegen")
        btnCreateCond.addActionListener { testCreateCondition() }
        buttonPanel.add(btnCreateCond)

        val btnCreateProc = JButton("Procedure (Therapie) anlegen")
        btnCreateProc.addActionListener { testCreateProcedure() }
        buttonPanel.add(btnCreateProc)

        val btnCreateDoc = JButton("DocumentRef anlegen")
        btnCreateDoc.addActionListener { testCreateDocumentReference() }
        buttonPanel.add(btnCreateDoc)

        val btnTransaction = JButton("Transaktion (Obs+Cond)")
        btnTransaction.addActionListener { testTransaction() }
        buttonPanel.add(btnTransaction)
        
        apiTestPanel.add(buttonPanel)
        
        val logArea = JTextArea(10, 50)
        logArea.isEditable = false
        val logScrollPane = JScrollPane(logArea)
        apiTestPanel.add(JLabel("Logs:"))
        apiTestPanel.add(logScrollPane)
        
        centerPanel.add(urlDetailsContainer, BorderLayout.NORTH)
        centerPanel.add(apiTestPanel, BorderLayout.CENTER)
        
        mainPanel.add(centerPanel, BorderLayout.CENTER)
        
        add(mainPanel)
        
        // Logger Helper
        fun log(msg: String) {
            logArea.append(msg + "\n")
            logArea.caretPosition = logArea.document.length
        }
        
        this.rootPane.putClientProperty("logFunc", ::log)
    }

    private fun log(msg: String) {
        @Suppress("UNCHECKED_CAST")
        val logFunc = rootPane.getClientProperty("logFunc") as? (String) -> Unit
        logFunc?.invoke(msg)
    }

    private fun testSearchPatient() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        executor.execute {
            try {
                log("Suche Patient für Kontext $kontext...")
                val patient = service.searchPatientByKontext(kontext)
                SwingUtilities.invokeLater {
                    if (patient != null) {
                        log("Patient gefunden: ID=${patient.idElement.idPart}")
                    } else {
                        log("Kein Patient für diesen Kontext gefunden.")
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unbekannter Fehler"
                SwingUtilities.invokeLater { log("Fehler bei Patientensuche: $errorMsg") }
                logger.error("Fehler bei Patientensuche", e)
            }
        }
    }

    private fun testCreateObservation() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)
        
        executor.execute {
            try {
                log("Lege Observation (Befund) an...")
                val outcome = service.createObservation(kontext, "befund", "Test-Befund via Demoapp")
                SwingUtilities.invokeLater { log("Ergebnis: ${outcome.issueFirstRep.severity} - ${outcome.issueFirstRep.diagnostics ?: "OK"}") }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Observation-Erstellung: ${e.message}") }
                logger.error("Fehler bei Observation-Erstellung", e)
            }
        }
    }

    private fun testCreateCondition() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)
        
        executor.execute {
            try {
                log("Lege Condition (Diagnose J06.9) an...")
                val outcome = service.createCondition(kontext, "J06.9", "Akute Infektion")
                SwingUtilities.invokeLater { log("Ergebnis: ${outcome.issueFirstRep.severity} - ${outcome.issueFirstRep.diagnostics ?: "OK"}") }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Condition-Erstellung: ${e.message}") }
                logger.error("Fehler bei Condition-Erstellung", e)
            }
        }
    }

    private fun testCreateProcedure() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)
        
        executor.execute {
            try {
                log("Lege Procedure (Therapie) an...")
                val outcome = service.createProcedure(kontext, "therapie", "Test-Therapie")
                SwingUtilities.invokeLater { log("Ergebnis: ${outcome.issueFirstRep.severity} - ${outcome.issueFirstRep.diagnostics ?: "OK"}") }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Procedure-Erstellung: ${e.message}") }
                logger.error("Fehler bei Procedure-Erstellung", e)
            }
        }
    }

    private fun testCreateDocumentReference() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)
        
        executor.execute {
            try {
                log("Lege DocumentReference (Freitext) an...")
                val outcome = service.createDocumentReference(kontext, "Test-Dokument", "Inhalt des Test-Dokuments")
                SwingUtilities.invokeLater { log("Ergebnis: ${outcome.issueFirstRep.severity} - ${outcome.issueFirstRep.diagnostics ?: "OK"}") }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei DocumentReference-Erstellung: ${e.message}") }
                logger.error("Fehler bei DocumentReference-Erstellung", e)
            }
        }
    }

    private fun testTransaction() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)
        
        executor.execute {
            try {
                log("Sende Transaction Bundle (Obs + Cond)...")
                val obs = Observation().apply {
                    val meta = Meta(); meta.addProfile(FhirConstants.PROFILE_OBSERVATION_BEFUND); this.meta = meta
                    status = Observation.ObservationStatus.FINAL
                    effective = DateTimeType(Date())
                    value = StringType("Befund aus Transaktion")
                }
                val cond = Condition().apply {
                    val meta = Meta(); meta.addProfile(FhirConstants.PROFILE_CONDITION_DIAGNOSE); this.meta = meta
                    code = CodeableConcept().addCoding(Coding(FhirConstants.CODE_SYSTEM_ICD_10_GM, "J06.9", "Akute Infektion"))
                    onset = DateTimeType(Date())
                }
                
                val bundle = service.sendTransactionBundle(kontext, listOf(obs, cond))
                SwingUtilities.invokeLater {
                    log("Transaktion abgeschlossen. Entries: ${bundle.entry.size}")
                    bundle.entry.forEach { entry ->
                        log(" - Status: ${entry.response.status}")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Transaktion: ${e.message}") }
                logger.error("Fehler bei Transaktion", e)
            }
        }
    }

    private fun createDetailRow(label: String, valueLabel: JLabel): JPanel {
        val panel = JPanel(BorderLayout(5, 5))
        panel.alignmentX = Component.LEFT_ALIGNMENT
        val l = JLabel(label)
        l.font = l.font.deriveFont(Font.BOLD)
        panel.add(l, BorderLayout.WEST)
        panel.add(valueLabel, BorderLayout.CENTER)
        return panel
    }

    fun handleUrl(url: String) {
        urlLabel.text = "<html><body style='width: 500px'>$url</body></html>"
        tableModel.rowCount = 0

        try {
            val uri = URI(url)
            protocolLabel.text = uri.scheme ?: "-"
            hostLabel.text = uri.host ?: "-"
            pathLabel.text = uri.path ?: "-"

            val query = uri.query
            if (query != null) {
                val params = query.split("&")
                for (param in params) {
                    val pair = param.split("=")
                    val key = URLDecoder.decode(pair[0], "UTF-8")
                    val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
                    tableModel.addRow(arrayOf(key, value))

                    // FHIR relevante Parameter extrahieren
                    when (key) {
                        AppConstants.QUERY_PARAM_KONTEXT_ID -> kontextId = value
                        AppConstants.QUERY_PARAM_FHIR_BASIS_URL -> fhirBasisUrl = value
                        AppConstants.QUERY_PARAM_OAUTH_TOKEN -> oAuthToken = value
                    }
                }
            }

            if (fhirBasisUrl != null && oAuthToken != null) {
                fhirService = FhirService(fhirBasisUrl!!, "7QwA7931lJSQfMKuTH4MQXLn4YEiNhE5tggnYKlY4HE", oAuthToken!!)
                log("FHIR-Service initialisiert für $fhirBasisUrl")
                logger.info("FHIR-Service initialisiert für {}", fhirBasisUrl)
            } else {
                log("FHIR-Service konnte nicht initialisiert werden (fhirBasisUrl oder oAuthToken fehlt)")
            }

        } catch (e: Exception) {
            logger.error("Fehler beim Parsen der URL", e)
            JOptionPane.showMessageDialog(this, "Fehler beim Parsen der URL: ${e.message}", "Fehler", JOptionPane.ERROR_MESSAGE)
        }

        toFront()
    }
}
