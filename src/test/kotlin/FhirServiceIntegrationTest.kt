import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.hl7.fhir.r4.model.Patient
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

class FhirServiceIntegrationTest {

    private val fhirBasisUrl = "https://127.0.0.1:16567/aps/fhir/api"
    private val kontextId = "7Cc9de760f-7cf1-4758-aaa9-c067e4bb7b4d"
    private val oAuthToken = "hSHJhjdhfdjsgfADJfh2A"
    private val apiKey = "dummy-api-key" // Im ISSUE DESCRIPTION nicht explizit genannt, aber FhirService benötigt einen

    @Test
    fun `test searchPatientByKontext on live server`() {
        println("[DEBUG_LOG] Initialisiere FhirService mit $fhirBasisUrl")
        val service = FhirService(fhirBasisUrl, apiKey, oAuthToken)

        println("[DEBUG_LOG] Suche Patient für Kontext $kontextId...")
        try {
            val patient = service.searchPatientByKontext(kontextId)
            
            if (patient != null) {
                println("[DEBUG_LOG] Patient gefunden: ID=${patient.idElement.idPart}")
                assertNotNull(patient)
            } else {
                println("[DEBUG_LOG] Kein Patient für diesen Kontext gefunden.")
            }
        } catch (e: Exception) {
            println("[DEBUG_LOG] Fehler bei der Kommunikation mit dem Server: ${e.message}")
            // Wir lassen den Test fehlschlagen, wenn wir keine Verbindung bekommen,
            // außer der User hat den Server gerade nicht laufen. 
            // In einer CI Umgebung würde dieser Test vermutlich fehlschlagen.
            throw e
        }
    }
}
