import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.model.api.annotation.ResourceDef
import ca.uhn.fhir.rest.client.api.IClientInterceptor
import ca.uhn.fhir.rest.client.api.IHttpRequest
import ca.uhn.fhir.rest.client.api.IHttpResponse
import ca.uhn.fhir.rest.client.interceptor.AdditionalRequestHeadersInterceptor
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.client.HttpClients
import org.hl7.fhir.r4.model.*
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import constants.FhirConstants

/** Neuer Telecom-Eintrag (Telefon, Fax oder Email) für Patient-Update. */
data class TelecomEntry(
    val system: ContactPoint.ContactPointSystem,
    val use: ContactPoint.ContactPointUse?,
    val value: String
)

/**
 * Die fünf deutschen Geschlechtswerte.
 * DIVERS ("D") und UNBESTIMMT ("X") erfordern eine FHIR-Extension auf dem genderElement;
 * ohne Extension würde der APS-Server `other` als UNBEKANNT interpretieren.
 */
enum class Geschlecht(
    val fhirGender: Enumerations.AdministrativeGender,
    val amtlichCode: String?   // null = keine Extension nötig
) {
    MAENNLICH(Enumerations.AdministrativeGender.MALE,    null),
    WEIBLICH (Enumerations.AdministrativeGender.FEMALE,  null),
    DIVERS   (Enumerations.AdministrativeGender.OTHER,   "D"),
    UNBESTIMMT(Enumerations.AdministrativeGender.OTHER,  "X"),
    UNBEKANNT(Enumerations.AdministrativeGender.UNKNOWN, null)
}

/** Felder, die beim Patient-Update gesetzt werden sollen. */
data class PatientUpdateData(
    val nachname: String,
    val vorname: String,
    val geburtsdatum: String,    // YYYY-MM-DD, leer = unverändert
    val geschlecht: Geschlecht?,
    val neuesTelecom: TelecomEntry?
)

/** Lokale Custom-Resource-Klasse analog zu APS FhirApiBefund (erweitert Observation, Ressourcenname "Befund"). */
@ResourceDef(name = "Befund", profile = FhirConstants.PROFILE_BEFUND)
class FhirApiBefund : Observation() {
    override fun fhirType(): String = "Befund"
}

class FhirService(private val baseUrl: String, private val apiKey: String, private val oAuthToken : String, private val providedCtx: FhirContext? = null) {
    private val logger = LoggerFactory.getLogger(FhirService::class.java)
    private val ctx = providedCtx ?: createConfiguredContext()
    private val client: ca.uhn.fhir.rest.client.api.IGenericClient
    private val lastUrlInterceptor = LastUrlInterceptor()

    private class LastUrlInterceptor : IClientInterceptor {
        var lastUrl: String? = null
            private set

        override fun interceptRequest(theRequest: IHttpRequest) {
            lastUrl = theRequest.uri
        }

        override fun interceptResponse(theResponse: IHttpResponse) {
            // Nothing to do
        }
    }

    private fun createConfiguredContext(): FhirContext {
        val context = FhirContext.forR4()

        // Metadata-Validierung deaktivieren
        context.restfulClientFactory.serverValidationMode = ca.uhn.fhir.rest.client.api.ServerValidationModeEnum.NEVER

        // SSL-Konfiguration für HTTPS setzen
        if (baseUrl.startsWith("https://")) {
            configureSsl(context)
        }

        return context
    }

    init {
        // Nochmals sicherstellen, dass Metadata-Validierung deaktiviert ist
        ctx.restfulClientFactory.serverValidationMode = ca.uhn.fhir.rest.client.api.ServerValidationModeEnum.NEVER

        if (baseUrl.startsWith("https://")) {
            configureSsl(ctx)
        }

        // Client erstellen
        client = ctx.newRestfulGenericClient(baseUrl)

        // Interceptor für die letzte URL registrieren
        client.registerInterceptor(lastUrlInterceptor)

        // API-Key Header hinzufügen
        val interceptor = AdditionalRequestHeadersInterceptor()
        interceptor.addHeaderValue(FhirConstants.HEADER_CONTENT_TYPE, "application/fhir+xml; charset=UTF-8")
        interceptor.addHeaderValue(FhirConstants.HEADER_AUTHORIZATION, "Bearer $oAuthToken")
        interceptor.addHeaderValue(FhirConstants.HEADER_PREFER, "return=OperationOutcome")
        interceptor.addHeaderValue(FhirConstants.HEADER_X_API_KEY, apiKey)
        interceptor.addHeaderValue(FhirConstants.HEADER_X_TREAT_WARNING_AS_ERROR, "true")
        client.registerInterceptor(interceptor)
    }

    private fun configureSsl(ctx: FhirContext) {
        try {
            // Dynamischen SSLContext verwenden, der Zertifikate pro Server akzeptiert
            val sslContext = TrustAllCerts.getDynamicSslContext()

            val sslSocketFactory = SSLConnectionSocketFactory(
                sslContext,
                NoopHostnameVerifier.INSTANCE
            )

            val connManager = org.apache.http.impl.conn.PoolingHttpClientConnectionManager(
                org.apache.http.config.RegistryBuilder.create<org.apache.http.conn.socket.ConnectionSocketFactory>()
                    .register("http", org.apache.http.conn.socket.PlainConnectionSocketFactory.getSocketFactory())
                    .register("https", sslSocketFactory)
                    .build()
            )
            connManager.maxTotal = 20
            connManager.defaultMaxPerRoute = 5

            val httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .build()

            val factory = ctx.restfulClientFactory
            factory.connectTimeout = 30000
            factory.socketTimeout = 30000
            factory.setPoolMaxTotal(20)
            factory.setPoolMaxPerRoute(5)

            factory.setHttpClient(httpClient)

            logger.info("SSL-Konfiguration für {} angewendet", baseUrl)

        } catch (e: Exception) {
            logger.error("Fehler bei der SSL-Konfiguration: {}", e.message)
        }
    }

    private fun addKontextIdentifier(resource: DomainResource, kontextId: String) {
        val identifier = Identifier()
        identifier.system = FhirConstants.IDENTIFIER_SYSTEM_KONTEXT
        identifier.value = kontextId
        
        when (resource) {
            is Patient -> resource.addIdentifier(identifier)
            is Observation -> resource.addIdentifier(identifier)
            is Condition -> resource.addIdentifier(identifier)
            is Procedure -> resource.addIdentifier(identifier)
            is DocumentReference -> resource.addIdentifier(identifier)
        }
    }

    private fun setProfile(resource: Resource, profileUrl: String) {
        val meta = resource.meta ?: Meta()
        meta.addProfile(profileUrl)
        resource.meta = meta
    }

    fun searchPatientByKontext(kontextId: String): Patient? {
        try {
            logger.debug("Suche Patient mit Kontext: {}", kontextId)

            val bundle = client.search<Bundle>()
                .forResource(Patient::class.java)
                .where(Patient.IDENTIFIER.exactly().systemAndCode(FhirConstants.IDENTIFIER_SYSTEM_KONTEXT, kontextId))
                .withAdditionalHeader(FhirConstants.HEADER_X_FHIR_PROFILE, FhirConstants.PROFILE_PATIENT)
                .returnBundle(Bundle::class.java)
                .execute()

            return if (bundle.hasEntry()) {
                bundle.entryFirstRep.resource as Patient
            } else {
                null
            }
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "searchPatientByKontext")
        }
    }

    fun searchPatientByName(family: String, given: String, birthdate: String): Bundle {
        try {
            return client.search<Bundle>()
                .forResource(Patient::class.java)
                .where(Patient.FAMILY.matches().value(family))
                .and(Patient.GIVEN.matches().value(given))
                .and(Patient.BIRTHDATE.exactly().day(birthdate))
                .withAdditionalHeader(FhirConstants.HEADER_X_FHIR_PROFILE, FhirConstants.PROFILE_PATIENT)
                .returnBundle(Bundle::class.java)
                .execute()
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "searchPatientByName")
        }
    }

    fun readPatient(id: String): Patient {
        try {
            return client.read()
                .resource(Patient::class.java)
                .withId(id)
                .withAdditionalHeader(FhirConstants.HEADER_X_FHIR_PROFILE, FhirConstants.PROFILE_PATIENT)
                .execute()
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "readPatient")
        }
    }

    fun updatePatient(patient: Patient): OperationOutcome {
        try {
            val versionId = patient.meta?.versionId
            if (versionId != null) {
                patient.idElement = IdType("Patient", patient.idElement.idPart, versionId)
            }
            val result = client.update()
                .resource(patient)
                .withAdditionalHeader(FhirConstants.HEADER_X_FHIR_PROFILE, FhirConstants.PROFILE_PATIENT)
                .execute()
            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "updatePatient")
        }
    }

    fun createObservation(kontextId: String, type: String, value: String): OperationOutcome {
        try {
            val obs = Observation()
            val profile = when (type.lowercase()) {
                "befund" -> FhirConstants.PROFILE_OBSERVATION_BEFUND
                "anamnese" -> FhirConstants.PROFILE_OBSERVATION_ANAMNESE
                "freitext" -> FhirConstants.PROFILE_OBSERVATION_FREITEXT
                else -> throw IllegalArgumentException("Unbekannter Observation-Typ")
            }
            
            setProfile(obs, profile)
            addKontextIdentifier(obs, kontextId)
            obs.status = Observation.ObservationStatus.FINAL
            obs.effective = DateTimeType(Date())
            obs.value = StringType(value)

            val result = client.create()
                .resource(obs)
                .execute()
            
            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "createObservation")
        }
    }

    fun createCondition(kontextId: String, icdCode: String, text: String): OperationOutcome {
        try {
            val cond = Condition()
            setProfile(cond, FhirConstants.PROFILE_CONDITION_DIAGNOSE)
            addKontextIdentifier(cond, kontextId)
            
            val code = CodeableConcept()
            val coding = code.addCoding()
            coding.system = FhirConstants.CODE_SYSTEM_ICD_10_GM
            coding.code = icdCode
            code.text = text
            cond.code = code
            cond.onset = DateTimeType(Date())

            val result = client.create()
                .resource(cond)
                .execute()
                
            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "createCondition")
        }
    }

    fun createProcedure(kontextId: String, type: String, text: String): OperationOutcome {
        try {
            val proc = Procedure()
            val profile = when (type.lowercase()) {
                "therapie" -> FhirConstants.PROFILE_PROCEDURE_THERAPIE
                "prozedere" -> FhirConstants.PROFILE_PROCEDURE_PROCEDERE
                else -> throw IllegalArgumentException("Unbekannter Procedure-Typ")
            }
            
            setProfile(proc, profile)
            addKontextIdentifier(proc, kontextId)
            proc.status = Procedure.ProcedureStatus.COMPLETED
            proc.code = CodeableConcept().setText(text)
            proc.performed = DateTimeType(Date())

            val result = client.create()
                .resource(proc)
                .execute()
                
            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "createProcedure")
        }
    }

    fun createDocumentReference(kontextId: String, title: String, content: String): OperationOutcome {
        try {
            val doc = DocumentReference()
            setProfile(doc, FhirConstants.PROFILE_DOCUMENT_REFERENCE_FREITEXT)
            addKontextIdentifier(doc, kontextId)
            
            doc.status = Enumerations.DocumentReferenceStatus.CURRENT
            doc.date = Date()
            
            val attachment = Attachment()
            attachment.contentType = "text/plain;charset=UTF-8"
            attachment.title = title
            attachment.data = content.toByteArray()
            
            doc.addContent().attachment = attachment

            val result = client.create()
                .resource(doc)
                .execute()
                
            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "createDocumentReference")
        }
    }

    fun createDocumentReferenceAnhang(kontextId: String, file: java.io.File, kuerzel: String = "Dokument"): OperationOutcome {
        try {
            val doc = DocumentReference()
            setProfile(doc, FhirConstants.PROFILE_DOCUMENT_REFERENCE_ANHANG)
            addKontextIdentifier(doc, kontextId)

            doc.status = Enumerations.DocumentReferenceStatus.CURRENT
            doc.date = Date()
            doc.description = file.name

            val kuerzelExt = org.hl7.fhir.r4.model.Extension()
            kuerzelExt.url = FhirConstants.EXTENSION_ANHANG_KUERZEL
            kuerzelExt.setValue(StringType(kuerzel))
            doc.addExtension(kuerzelExt)

            val attachment = Attachment()
            attachment.contentType = java.nio.file.Files.probeContentType(file.toPath()) ?: "application/octet-stream"
            attachment.title = file.name
            attachment.creation = Date()
            attachment.data = file.readBytes()

            doc.addContent().attachment = attachment

            val result = client.create()
                .resource(doc)
                .execute()

            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "createDocumentReferenceAnhang")
        }
    }

    fun sendTransactionBundle(kontextId: String, resources: List<Resource>): Bundle {
        try {
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.TRANSACTION
            
            for (res in resources) {
                if (res is DomainResource) {
                    addKontextIdentifier(res, kontextId)
                }
                
                val entry = bundle.addEntry()
                entry.resource = res
                val request = entry.request
                request.method = Bundle.HTTPVerb.POST
                request.url = res.resourceType.name
            }
            
            return client.transaction()
                .withBundle(bundle)
                .execute()
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "sendTransactionBundle")
        }
    }

    fun createPatient(kontextId: String): OperationOutcome {
        try {
            val patient = Patient()
            setProfile(patient, FhirConstants.PROFILE_PATIENT)
            addKontextIdentifier(patient, kontextId)

            val name = patient.addName()
            name.family = "Mustermann"
            name.addGiven("Max")

            patient.birthDateElement = DateType("1980-04-12")
            patient.gender = Enumerations.AdministrativeGender.MALE

            val result = client.create()
                .resource(patient)
                .execute()
            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "createPatient")
        }
    }

    fun updatePatient(patientId: String, data: PatientUpdateData): OperationOutcome {
        try {
            val patient = readPatient(patientId)

            // Namensdaten überschreiben
            patient.name.clear()
            val name = patient.addName()
            name.family = data.nachname
            if (data.vorname.isNotBlank()) name.addGiven(data.vorname)

            // Geburtsdatum setzen (wenn angegeben)
            if (data.geburtsdatum.isNotBlank()) {
                patient.birthDateElement = DateType(data.geburtsdatum)
            }

            // Geschlecht setzen (wenn ausgewählt)
            // DIVERS und UNBESTIMMT benötigen zusätzlich die Extension gender-amtlich-de,
            // damit der APS-Server sie von UNBEKANNT unterscheiden kann.
            data.geschlecht?.let { g ->
                patient.gender = g.fhirGender
                if (g.amtlichCode != null) {
                    patient.genderElement.addExtension(
                        Extension(
                            "http://fhir.de/StructureDefinition/gender-amtlich-de",
                            Coding("http://fhir.de/CodeSystem/gender-amtlich-de", g.amtlichCode, null)
                        )
                    )
                }
            }

            // Neuen Telecom-Eintrag anhängen (wenn angegeben)
            data.neuesTelecom?.let { tc ->
                patient.addTelecom().apply {
                    system = tc.system
                    use = tc.use
                    value = tc.value
                }
            }

            // Version aus der ID entfernen: PUT /Patient/{id} statt /_history/{version}
            // Der APS-Server löst bei /_history/-URL einen Versionskonflikt-Check aus (409),
            // ohne Version-Suffix wird dieser Check übersprungen.
            patient.setId(patient.idElement.toUnqualifiedVersionless())
            val result = client.update().resource(patient).execute()
            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "updatePatient")
        }
    }

    fun readOrganization(id: String): Organization {
        try {
            return client.read()
                .resource(Organization::class.java)
                .withId(id)
                .withAdditionalHeader(FhirConstants.HEADER_X_FHIR_PROFILE, FhirConstants.PROFILE_ORGANIZATION)
                .execute()
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "readOrganization")
        }
    }

    fun readPractitioner(id: String): Practitioner {
        try {
            return client.read()
                .resource(Practitioner::class.java)
                .withId(id)
                .withAdditionalHeader(FhirConstants.HEADER_X_FHIR_PROFILE, FhirConstants.PROFILE_PRACTITIONER)
                .execute()
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "readPractitioner")
        }
    }

    fun readEncounter(kontextId: String): Encounter {
        try {
            return client.read()
                .resource(Encounter::class.java)
                .withId(kontextId)
                .execute()
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "readEncounter")
        }
    }

    fun searchOrganization(name: String): Bundle {
        try {
            return client.search<Bundle>()
                .forResource(Organization::class.java)
                .where(Organization.NAME.matches().value(name))
                .returnBundle(Bundle::class.java)
                .execute()
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "searchOrganization")
        }
    }

    fun searchPractitioner(name: String): Bundle {
        try {
            return client.search<Bundle>()
                .forResource(Practitioner::class.java)
                .where(Practitioner.FAMILY.matches().value(name))
                .returnBundle(Bundle::class.java)
                .execute()
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "searchPractitioner")
        }
    }

    fun createBefund(kontextId: String, value: String): OperationOutcome {
        try {
            val befund = FhirApiBefund()
            addKontextIdentifier(befund, kontextId)
            befund.status = Observation.ObservationStatus.FINAL
            befund.effective = DateTimeType(Date())
            befund.value = StringType(value)

            val result = client.create()
                .resource(befund)
                .execute()
            return result.operationOutcome as OperationOutcome
        } catch (e: Exception) {
            throw wrapExceptionWithUrl(e, "createBefund")
        }
    }

    private fun wrapExceptionWithUrl(e: Exception, methodName: String): Exception {
        val url = lastUrlInterceptor.lastUrl ?: baseUrl
        println("[DEBUG] Fehler bei $methodName - URL war: $url")
        if (e is ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException) {
            val msg = e.message ?: ""
            if (e.statusCode == 403 && msg.contains("fehlt oder ist ungültig")) {
                return Exception("Demo-API-Key-Limit erreicht — bitte APS-Server neu starten. (URL: $url)", e)
            }
            return Exception("$msg (URL: $url)", e)
        }
        return e
    }
}
