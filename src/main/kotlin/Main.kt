import constants.AppConstants
import constants.FhirConstants
import deviceflow.DeviceFlowConfig
import deviceflow.DeviceFlowPanel
import org.hl7.fhir.r4.model.*
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Font
import java.net.URI
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.Executors
import javax.swing.*
import javax.swing.table.DefaultTableModel

fun main(args: Array<String>) {
    // Windows-Protokoll-Registrierung (im User-Kontext HKCU)
    WindowsProtocolHandler.registerIfNecessary()

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
    private val urlLabel = JTextField("Warte auf URL-Aufruf...").apply { isEditable = false; border = null; background = UIManager.getColor("Panel.background") }
    private val protocolLabel = JTextField("-").apply { isEditable = false; border = null; background = UIManager.getColor("Panel.background") }
    private val hostLabel = JTextField("-").apply { isEditable = false; border = null; background = UIManager.getColor("Panel.background") }
    private val pathLabel = JTextField("-").apply { isEditable = false; border = null; background = UIManager.getColor("Panel.background") }
    private val tableModel = object : DefaultTableModel(arrayOf("Parameter", "Wert"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JTable(tableModel)

    private var kontextId: String? = null
    private var fhirBasisUrl: String? = null
    private var oAuthToken: String? = null
    private var fhirService: FhirService? = null

    // Accumulates deep-link params for Device Flow pre-fill
    private val deepLinkParams = mutableMapOf<String, String>()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(950, 780)
        setLocationRelativeTo(null)

        val mainPanel = JPanel(BorderLayout(10, 10))
        mainPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        
        // Titelbereich mit Device-Flow-Button
        val titlePanel = JPanel(BorderLayout(10, 0))
        val titleLabel = JLabel("T2demo FHIR API Demo", JLabel.CENTER)
        titleLabel.font = Font("Arial", Font.BOLD, 20)
        val btnDeviceFlow = JButton("Standalone-Anmeldung (Device Flow)")
        btnDeviceFlow.addActionListener { showDeviceFlowMode() }
        titlePanel.add(titleLabel, BorderLayout.CENTER)
        titlePanel.add(btnDeviceFlow, BorderLayout.EAST)
        mainPanel.add(titlePanel, BorderLayout.NORTH)

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
        
        table.isEnabled = true
        val scrollPane = JScrollPane(table)
        scrollPane.preferredSize = java.awt.Dimension(700, 150)
        
        val urlDetailsContainer = JPanel(BorderLayout())
        urlDetailsContainer.add(detailsPanel, BorderLayout.NORTH)
        urlDetailsContainer.add(scrollPane, BorderLayout.CENTER)
        
        // API Test Panel
        val apiTestPanel = JPanel()
        apiTestPanel.layout = BoxLayout(apiTestPanel, BoxLayout.Y_AXIS)
        apiTestPanel.border = BorderFactory.createTitledBorder("API-Tests")
        
        val buttonPanel = JPanel(java.awt.GridLayout(0, 4, 6, 6))
        
        val btnSearchPatient = JButton("Patient (Kontext) suchen")
        btnSearchPatient.addActionListener { testSearchPatient() }
        buttonPanel.add(btnSearchPatient)

        val btnSearchPatientByName = JButton("Patient (Name) suchen")
        btnSearchPatientByName.addActionListener { testSearchPatientByName() }
        buttonPanel.add(btnSearchPatientByName)
        
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

        val btnUploadDoc = JButton("Dokument hochladen")
        btnUploadDoc.addActionListener { testUploadDocument() }
        buttonPanel.add(btnUploadDoc)

        val btnTransaction = JButton("Transaktion (Obs+Cond)")
        btnTransaction.addActionListener { testTransaction() }
        buttonPanel.add(btnTransaction)

        val btnCreatePatient = JButton("Patient anlegen")
        btnCreatePatient.addActionListener { testCreatePatient() }
        buttonPanel.add(btnCreatePatient)

        val btnUpdatePatient = JButton("Patient aktualisieren")
        btnUpdatePatient.addActionListener { testUpdatePatient() }
        buttonPanel.add(btnUpdatePatient)

        val btnReadEncounter = JButton("Encounter lesen")
        btnReadEncounter.addActionListener { testReadEncounter() }
        buttonPanel.add(btnReadEncounter)

        val btnSearchOrg = JButton("Organisation suchen")
        btnSearchOrg.addActionListener { testSearchOrganization() }
        buttonPanel.add(btnSearchOrg)

        val btnSearchPract = JButton("Practitioner suchen")
        btnSearchPract.addActionListener { testSearchPractitioner() }
        buttonPanel.add(btnSearchPract)

        val btnCreateBefund = JButton("Befund anlegen")
        btnCreateBefund.addActionListener { testCreateBefund() }
        buttonPanel.add(btnCreateBefund)
        
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

    fun initializeFhirService(fhirUrl: String, kontext: String, accessToken: String) {
        fhirBasisUrl = fhirUrl
        kontextId = kontext
        oAuthToken = accessToken
        fhirService = FhirService(fhirUrl, AppConstants.API_KEY, accessToken)
        log("FHIR-Service initialisiert für $fhirUrl")
        logger.info("FHIR-Service initialisiert für {}", fhirUrl)
    }

    fun showDeviceFlowMode(config: DeviceFlowConfig? = null) {
        val effectiveConfig = config ?: DeviceFlowConfig.fromDeepLinkParams(deepLinkParams)
        val dialog = JDialog(this, "Standalone-Anmeldung (Device Flow)", true)
        val panel = DeviceFlowPanel(
            initialConfig = effectiveConfig,
            initialFhirUrl = fhirBasisUrl ?: "",
            initialKontextId = kontextId ?: ""
        ) { fhirUrl, kontext, token ->
            SwingUtilities.invokeLater {
                initializeFhirService(fhirUrl, kontext, token)
                dialog.dispose()
            }
        }
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.contentPane = panel
        dialog.pack()
        dialog.setSize(560, dialog.height.coerceAtLeast(480))
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
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
                        log("=== Patient gefunden ===")
                        log("ID:          ${patient.idElement.idPart}")
                        log("Version:     ${patient.meta?.versionId ?: "-"}")
                        val name = patient.nameFirstRep
                        log("Name:        ${name.family ?: "-"}, ${name.givenAsSingleString.ifBlank { "-" }}")
                        log("Geburtsdatum:${patient.birthDateElement?.valueAsString ?: "-"}")
                        log("Geschlecht:  ${patient.gender?.display ?: "-"}")
                        patient.address.forEach { adr ->
                            val typ = adr.type?.display ?: adr.use?.display ?: "Adresse"
                            val strasse = adr.line.joinToString(" ") { it.value }
                            log("$typ:    ${strasse.ifBlank { "-" }}, ${adr.postalCode ?: ""} ${adr.city ?: ""} ${adr.country ?: ""}".trimEnd())
                        }
                        if (patient.telecom.isEmpty()) {
                            log("Telefon/Email: (keine Einträge)")
                        } else {
                            patient.telecom.forEach { tc ->
                                val system = tc.system?.display ?: "?"
                                val use = tc.use?.display?.let { " ($it)" } ?: ""
                                val kommentar = tc.getExtensionByUrl("https://fhir.t2med.de/StructureDefinition/FhirApiKontaktinformationKommentar")
                                    ?.value?.primitiveValue()?.let { " [Kommentar: $it]" } ?: ""
                                val kategorie = tc.getExtensionByUrl("https://fhir.t2med.de/StructureDefinition/FhirApiTelefonnummerKategorie")
                                    ?.value?.primitiveValue()?.let { " [Kategorie: $it]" } ?: ""
                                log("$system$use: ${tc.value ?: "-"}$kategorie$kommentar")
                            }
                        }
                        patient.identifier.forEach { id ->
                            log("Identifier:  system=${id.system ?: "-"}, value=${id.value ?: "-"}")
                        }
                        log("========================")
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

    private fun testSearchPatientByName() {
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        val family = JOptionPane.showInputDialog(this, "Nachname:", "Patient suchen", JOptionPane.QUESTION_MESSAGE)
            ?: return
        val given = JOptionPane.showInputDialog(this, "Vorname (leer = beliebig):", "Patient suchen", JOptionPane.QUESTION_MESSAGE)
            ?: return
        val birthdate = JOptionPane.showInputDialog(this, "Geburtsdatum (YYYY-MM-DD, leer = beliebig):", "Patient suchen", JOptionPane.QUESTION_MESSAGE)
            ?: return

        executor.execute {
            try {
                log("Suche Patient: Nachname=\"$family\", Vorname=\"$given\", Geburtsdatum=\"$birthdate\"...")
                val bundle = service.searchPatientByName(family, given, birthdate)
                SwingUtilities.invokeLater {
                    log("${bundle.total} Treffer gefunden.")
                    bundle.entry.take(5).forEach { e ->
                        val p = e.resource as? org.hl7.fhir.r4.model.Patient
                        if (p != null) {
                            val name = p.nameFirstRep
                            val geb = p.birthDateElement?.valueAsString ?: "-"
                            log(" - ${name.family ?: "-"}, ${name.givenAsSingleString.ifBlank { "-" }} | Geb.: $geb | ID: ${p.idElement.idPart}")
                        }
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Patientensuche: ${e.message}") }
                logger.error("Fehler bei Patientensuche (Name)", e)
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

    private fun testUploadDocument() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        val file = if (System.getProperty("os.name").lowercase().contains("mac")) {
            val dialog = java.awt.FileDialog(this, "Dokument auswählen", java.awt.FileDialog.LOAD)
            dialog.isVisible = true
            if (dialog.file == null) return
            java.io.File(dialog.directory, dialog.file)
        } else {
            val chooser = JFileChooser()
            chooser.dialogTitle = "Dokument auswählen"
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
            chooser.selectedFile
        }

        executor.execute {
            try {
                log("Lade Dokument hoch: ${file.name} (${file.length()} Bytes)...")
                val outcome = service.createDocumentReferenceAnhang(kontext, file)
                SwingUtilities.invokeLater {
                    log("Ergebnis: ${outcome.issueFirstRep.severity} - ${outcome.issueFirstRep.diagnostics ?: "OK"}")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler beim Dokument-Upload: ${e.message}") }
                logger.error("Fehler beim Dokument-Upload", e)
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

    private fun testCreatePatient() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        executor.execute {
            try {
                log("Lege Patient (Max Mustermann, 1980-04-12) an...")
                val outcome = service.createPatient(kontext)
                SwingUtilities.invokeLater { log("Ergebnis: ${outcome.issueFirstRep.severity} - ${outcome.issueFirstRep.diagnostics ?: "OK"}") }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Patient-Erstellung: ${e.message}") }
                logger.error("Fehler bei Patient-Erstellung", e)
            }
        }
    }

    private fun testUpdatePatient() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        executor.execute {
            try {
                log("Suche Patient für Kontext $kontext (für Update)...")
                val patient = service.searchPatientByKontext(kontext)
                if (patient == null) {
                    SwingUtilities.invokeLater { log("Kein Patient gefunden — Update nicht möglich.") }
                    return@execute
                }
                val patientId = patient.idElement.idPart
                log("Patient gefunden: ID=$patientId — öffne Update-Dialog...")

                val dialogRef = java.util.concurrent.atomic.AtomicReference<PatientUpdateDialog>()
                SwingUtilities.invokeAndWait {
                    val dlg = PatientUpdateDialog(this@DemoApp, patient)
                    dialogRef.set(dlg)
                    dlg.isVisible = true
                }
                val updateData = dialogRef.get().result ?: run {
                    SwingUtilities.invokeLater { log("Update abgebrochen.") }
                    return@execute
                }
                val outcome = service.updatePatient(patientId, updateData)
                SwingUtilities.invokeLater { log("Ergebnis: ${outcome.issueFirstRep.severity} - ${outcome.issueFirstRep.diagnostics ?: "OK"}") }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Patient-Update: ${e.message}") }
                logger.error("Fehler bei Patient-Update", e)
            }
        }
    }

    private fun testReadEncounter() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        executor.execute {
            try {
                log("Lese Encounter für Kontext-ID $kontext...")
                val encounter = service.readEncounter(kontext)

                val patientName = encounter.subject?.referenceElement?.idPart
                    ?.takeIf { it.isNotBlank() }
                    ?.let { id ->
                        try {
                            val p = service.readPatient(id)
                            val n = p.nameFirstRep
                            val geb = p.birthDateElement?.valueAsString ?: "-"
                            "${n.family ?: "-"}, ${n.givenAsSingleString.ifBlank { "-" }} (Geb.: $geb)"
                        } catch (e: Exception) {
                            logger.warn("Patient-Referenz nicht auflösbar: {}", e.message)
                            "nicht auflösbar"
                        }
                    } ?: "-"

                val orgName = encounter.serviceProvider?.referenceElement?.idPart
                    ?.takeIf { it.isNotBlank() }
                    ?.let { id ->
                        try { service.readOrganization(id).name ?: "-" } catch (e: Exception) {
                            logger.warn("Organisation nicht auflösbar: {}", e.message)
                            "nicht auflösbar"
                        }
                    } ?: "-"

                val practitionerNames = encounter.participant
                    .mapNotNull { it.individual?.referenceElement?.idPart?.takeIf { id -> id.isNotBlank() } }
                    .map { id ->
                        try {
                            val pr = service.readPractitioner(id)
                            val n = pr.nameFirstRep
                            "${n.prefix.joinToString(" ") { p -> p.value }} ${n.family ?: "-"}, ${n.givenAsSingleString.ifBlank { "-" }}".trim()
                        } catch (e: Exception) {
                            logger.warn("Practitioner nicht auflösbar: {}", e.message)
                            "nicht auflösbar"
                        }
                    }.ifEmpty { listOf("-") }

                SwingUtilities.invokeLater {
                    log("=== Encounter gelesen ===")
                    log("ID:           ${encounter.idElement.idPart}")
                    log("Status:       ${encounter.status}")
                    log("Patient:      $patientName")
                    log("Organisation: $orgName")
                    practitionerNames.forEach { log("Arzt:         $it") }
                    log("========================")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Encounter-Lesen: ${e.message}") }
                logger.error("Fehler bei Encounter-Lesen", e)
            }
        }
    }

    private fun testSearchOrganization() {
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        val name = JOptionPane.showInputDialog(this, "Organisationsname (leer = alle):", "Organisation suchen", JOptionPane.QUESTION_MESSAGE)
            ?: return

        executor.execute {
            try {
                log("Suche Organisation: \"$name\"...")
                val bundle = service.searchOrganization(name)
                SwingUtilities.invokeLater {
                    log("${bundle.total} Treffer gefunden.")
                    bundle.entry.take(5).forEach { e ->
                        val org = e.resource as? org.hl7.fhir.r4.model.Organization
                        log(" - ${org?.name ?: org?.idElement?.idPart ?: "?"}")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Organisations-Suche: ${e.message}") }
                logger.error("Fehler bei Organisations-Suche", e)
            }
        }
    }

    private fun testSearchPractitioner() {
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        val name = JOptionPane.showInputDialog(this, "Nachname (leer = alle):", "Practitioner suchen", JOptionPane.QUESTION_MESSAGE)
            ?: return

        executor.execute {
            try {
                log("Suche Practitioner: \"$name\"...")
                val bundle = service.searchPractitioner(name)
                SwingUtilities.invokeLater {
                    log("${bundle.total} Treffer gefunden.")
                    bundle.entry.take(5).forEach { e ->
                        val pract = e.resource as? org.hl7.fhir.r4.model.Practitioner
                        val famName = pract?.nameFirstRep?.family ?: pract?.idElement?.idPart ?: "?"
                        log(" - $famName")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Practitioner-Suche: ${e.message}") }
                logger.error("Fehler bei Practitioner-Suche", e)
            }
        }
    }

    private fun testCreateBefund() {
        val kontext = kontextId ?: return log(AppConstants.ERROR_KONTEXT_ID_MISSING)
        val service = fhirService ?: return log(AppConstants.ERROR_FHIR_SERVICE_NOT_INITIALIZED)

        executor.execute {
            try {
                log("Lege Befund an...")
                val outcome = service.createBefund(kontext, "Test-Befund via Demoapp")
                SwingUtilities.invokeLater { log("Ergebnis: ${outcome.issueFirstRep.severity} - ${outcome.issueFirstRep.diagnostics ?: "OK"}") }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { log("Fehler bei Befund-Erstellung: ${e.message}") }
                logger.error("Fehler bei Befund-Erstellung", e)
            }
        }
    }

    private fun createDetailRow(label: String, valueLabel: JComponent): JPanel {
        val panel = JPanel(BorderLayout(5, 5))
        panel.alignmentX = LEFT_ALIGNMENT
        val l = JLabel(label)
        l.font = l.font.deriveFont(Font.BOLD)
        panel.add(l, BorderLayout.WEST)
        panel.add(valueLabel, BorderLayout.CENTER)
        return panel
    }

    fun handleUrl(url: String) {
        urlLabel.text = url
        urlLabel.caretPosition = 0
        tableModel.rowCount = 0
        deepLinkParams.clear()

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
                    deepLinkParams[key] = value

                    when (key) {
                        AppConstants.QUERY_PARAM_KONTEXT_ID -> kontextId = value
                        AppConstants.QUERY_PARAM_FHIR_BASIS_URL -> fhirBasisUrl = value
                        AppConstants.QUERY_PARAM_OAUTH_TOKEN -> oAuthToken = value
                    }
                }
            }

            if (fhirBasisUrl != null && oAuthToken != null) {
                initializeFhirService(fhirBasisUrl!!, kontextId ?: "", oAuthToken!!)
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

/**
 * Dialog zum Bearbeiten von Patientendaten.
 * Felder werden aus der übergebenen Patient-Ressource vorausgefüllt.
 * Nach Klick auf "Aktualisieren" steht das Ergebnis in [result].
 */
class PatientUpdateDialog(parent: JFrame, patient: org.hl7.fhir.r4.model.Patient) :
    JDialog(parent, "Patient aktualisieren", true) {

    var result: PatientUpdateData? = null
        private set

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = java.awt.BorderLayout(10, 10)
        val panel = JPanel(java.awt.GridBagLayout())
        panel.border = BorderFactory.createEmptyBorder(12, 12, 4, 12)
        val gbc = java.awt.GridBagConstraints().apply {
            insets = java.awt.Insets(4, 4, 4, 4)
            anchor = java.awt.GridBagConstraints.WEST
        }

        fun label(row: Int, text: String) {
            gbc.gridx = 0; gbc.gridy = row; gbc.fill = java.awt.GridBagConstraints.NONE
            panel.add(JLabel(text), gbc)
        }
        fun field(row: Int, comp: JComponent) {
            gbc.gridx = 1; gbc.gridy = row; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(comp, gbc)
            gbc.weightx = 0.0
        }

        // ── Stammdaten ──────────────────────────────────────────────────────
        val tfNachname = JTextField(patient.nameFirstRep.family ?: "", 20)
        val tfVorname  = JTextField(patient.nameFirstRep.givenAsSingleString, 20)
        val tfGeburt   = JTextField(patient.birthDateElement?.valueAsString ?: "", 12)

        val geschlechtItems = arrayOf("-", "Männlich", "Weiblich", "Divers", "Unbestimmt", "Unbekannt")
        val cbGeschlecht = JComboBox(geschlechtItems)
        // OTHER ausdifferenzieren: Amtlich-Extension auf genderElement prüfen (Code "D" = divers, "X" = unbestimmt)
        val amtlichCode = patient.genderElement
            ?.getExtensionByUrl("http://fhir.de/StructureDefinition/gender-amtlich-de")
            ?.value?.let { (it as? Coding)?.code ?: it.primitiveValue() }
        when {
            patient.gender == Enumerations.AdministrativeGender.MALE    -> cbGeschlecht.selectedItem = "Männlich"
            patient.gender == Enumerations.AdministrativeGender.FEMALE  -> cbGeschlecht.selectedItem = "Weiblich"
            patient.gender == Enumerations.AdministrativeGender.UNKNOWN -> cbGeschlecht.selectedItem = "Unbekannt"
            patient.gender == Enumerations.AdministrativeGender.OTHER && amtlichCode == "X" -> cbGeschlecht.selectedItem = "Unbestimmt"
            patient.gender == Enumerations.AdministrativeGender.OTHER   -> cbGeschlecht.selectedItem = "Divers"
            else -> cbGeschlecht.selectedItem = "-"
        }

        label(0, "Nachname:"); field(0, tfNachname)
        label(1, "Vorname:");  field(1, tfVorname)
        label(2, "Geburtsdatum (YYYY-MM-DD):"); field(2, tfGeburt)
        label(3, "Geschlecht:"); field(3, cbGeschlecht)

        // ── Vorhandene Kontaktdaten (read-only) ──────────────────────────────
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.fill = java.awt.GridBagConstraints.NONE
        panel.add(JLabel("Vorhandene Kontaktdaten:"), gbc)
        gbc.gridwidth = 1

        val existingText = if (patient.telecom.isEmpty()) "(keine)" else
            patient.telecom.joinToString("\n") { tc ->
                val sys = tc.system?.display ?: "?"
                val use = tc.use?.display?.let { " ($it)" } ?: ""
                "$sys$use: ${tc.value ?: "-"}"
            }
        val taExisting = JTextArea(existingText, 3, 30).apply {
            isEditable = false
            background = UIManager.getColor("Panel.background")
            lineWrap = true; wrapStyleWord = true
        }
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2
        gbc.fill = java.awt.GridBagConstraints.BOTH; gbc.weighty = 0.3
        panel.add(JScrollPane(taExisting), gbc)
        gbc.gridwidth = 1; gbc.weighty = 0.0

        // ── Neuen Kontakt hinzufügen ─────────────────────────────────────────
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2; gbc.fill = java.awt.GridBagConstraints.NONE
        panel.add(JLabel("Neuen Kontakt hinzufügen:"), gbc)
        gbc.gridwidth = 1

        val cbTcTyp = JComboBox(arrayOf("(keiner)", "Telefon", "Fax", "Email"))
        val cbTcUse = JComboBox(arrayOf("-", "Privat", "Geschäftlich", "Mobil"))
        val tfTcWert = JTextField(20)

        label(7, "Typ:"); field(7, cbTcTyp)
        label(8, "Verwendung:"); field(8, cbTcUse)
        label(9, "Wert:"); field(9, tfTcWert)

        add(panel, java.awt.BorderLayout.CENTER)

        // ── Buttons ──────────────────────────────────────────────────────────
        val btnCancel = JButton("Abbrechen")
        val btnOk     = JButton("Aktualisieren")
        btnOk.isDefaultCapable = true
        getRootPane().defaultButton = btnOk

        btnCancel.addActionListener { dispose() }
        btnOk.addActionListener {
            val tcTypStr = cbTcTyp.selectedItem as String
            val tcSystem = when (tcTypStr) {
                "Telefon" -> ContactPoint.ContactPointSystem.PHONE
                "Fax"     -> ContactPoint.ContactPointSystem.FAX
                "Email"   -> ContactPoint.ContactPointSystem.EMAIL
                else      -> null
            }
            val tcUseStr = cbTcUse.selectedItem as String
            val tcUse = when (tcUseStr) {
                "Privat"       -> ContactPoint.ContactPointUse.HOME
                "Geschäftlich" -> ContactPoint.ContactPointUse.WORK
                "Mobil"        -> ContactPoint.ContactPointUse.MOBILE
                else           -> null
            }
            val tcWert = tfTcWert.text.trim()
            val neuesTelecom = if (tcSystem != null && tcWert.isNotBlank())
                TelecomEntry(tcSystem, tcUse, tcWert) else null

            val geschlecht = when (cbGeschlecht.selectedItem as String) {
                "Männlich"   -> Geschlecht.MAENNLICH
                "Weiblich"   -> Geschlecht.WEIBLICH
                "Divers"     -> Geschlecht.DIVERS
                "Unbestimmt" -> Geschlecht.UNBESTIMMT
                "Unbekannt"  -> Geschlecht.UNBEKANNT
                else         -> null
            }

            result = PatientUpdateData(
                nachname      = tfNachname.text.trim(),
                vorname       = tfVorname.text.trim(),
                geburtsdatum  = tfGeburt.text.trim(),
                geschlecht    = geschlecht,
                neuesTelecom  = neuesTelecom
            )
            dispose()
        }

        val btnPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT))
        btnPanel.border = BorderFactory.createEmptyBorder(0, 12, 8, 12)
        btnPanel.add(btnCancel)
        btnPanel.add(btnOk)
        add(btnPanel, java.awt.BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(parent)
    }
}
