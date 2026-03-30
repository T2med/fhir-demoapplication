import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory
import org.apache.http.client.HttpClient
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class FhirServiceSslTest {

    @Test
    fun `test insecure SSL configuration for localhost`() {
        val baseUrl = "https://localhost:8080/fhir"
        val mockCtx = mock<FhirContext>()
        val mockFactory = mock<IRestfulClientFactory>()
        
        whenever(mockCtx.restfulClientFactory).thenReturn(mockFactory)
        
        // Der Aufruf von newRestfulGenericClient muss gemockt werden, damit der Konstruktor durchläuft
        whenever(mockCtx.newRestfulGenericClient(any())).thenReturn(mock())

        FhirService(baseUrl, "test-api-key", "test-token", mockCtx)

        // Verifizieren, dass setHttpClient aufgerufen wurde (zweimal: einmal in ctx.run und einmal in init)
        verify(mockFactory, atLeastOnce()).setHttpClient(any<HttpClient>())
    }

    @Test
    fun `test no SSL configuration for HTTP URLs`() {
        val baseUrl = "http://127.0.0.1:8080/fhir"
        val mockCtx = mock<FhirContext>()
        val mockFactory = mock<IRestfulClientFactory>()

        whenever(mockCtx.restfulClientFactory).thenReturn(mockFactory)
        whenever(mockCtx.newRestfulGenericClient(any())).thenReturn(mock())

        FhirService(baseUrl, "test-api-key", "test-token", mockCtx)

        // HTTP benötigt keine SSL-Konfiguration
        verify(mockFactory, never()).setHttpClient(any<HttpClient>())
    }

    @Test
    fun `test insecure SSL configuration for public HTTPS URL`() {
        val baseUrl = "https://fhir.t2med.de/api"
        val mockCtx = mock<FhirContext>()
        val mockFactory = mock<IRestfulClientFactory>()

        whenever(mockCtx.restfulClientFactory).thenReturn(mockFactory)
        whenever(mockCtx.newRestfulGenericClient(any())).thenReturn(mock())

        FhirService(baseUrl, "test-api-key", "test-token", mockCtx)

        // Verifizieren, dass setHttpClient aufgerufen wurde (für alle HTTPS-URLs)
        verify(mockFactory, atLeastOnce()).setHttpClient(any<HttpClient>())
    }
}
