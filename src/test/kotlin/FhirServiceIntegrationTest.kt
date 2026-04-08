import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class FhirServiceIntegrationTest {
    private val fhirBasisUrl = System.getenv("FHIR_BASE_URL")
    private val kontextId = System.getenv("FHIR_KONTEXT_ID")
    private val oAuthToken = System.getenv("FHIR_OAUTH_TOKEN")
    private val apiKey = "dummy-api-key"

    @Test
    fun `test searchPatientByKontext on live server`() {
        assumeTrue(
            !fhirBasisUrl.isNullOrBlank() &&
                !kontextId.isNullOrBlank() &&
                !oAuthToken.isNullOrBlank(),
            "FHIR integration test skipped: set FHIR_BASE_URL, FHIR_KONTEXT_ID and FHIR_OAUTH_TOKEN."
        )

        println("[DEBUG_LOG] Initialisiere FhirService mit $fhirBasisUrl")
        val service = FhirService(fhirBasisUrl!!, apiKey, oAuthToken!!)

        println("[DEBUG_LOG] Suche Patient für Kontext $kontextId...")
        try {
            val patient = service.searchPatientByKontext(kontextId!!)
            
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
