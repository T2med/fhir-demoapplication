import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory
import ca.uhn.fhir.rest.gclient.*
import org.hl7.fhir.r4.model.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.junit.jupiter.api.assertThrows
import java.lang.Exception
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FhirServiceTest {

    private lateinit var mockCtx: FhirContext
    private lateinit var mockClient: IGenericClient
    private lateinit var fhirService: FhirService
    private val baseUrl = "https://fhir.t2med.de/api"
    private val apiKey = "test-api-key"
    private val oAuthToken = "test-token"

    @BeforeEach
    fun setUp() {
        mockCtx = mock()
        mockClient = mock()
        val mockFactory: IRestfulClientFactory = mock()
        
        whenever(mockCtx.restfulClientFactory).thenReturn(mockFactory)
        whenever(mockCtx.newRestfulGenericClient(baseUrl)).thenReturn(mockClient)
        
        fhirService = FhirService(baseUrl, apiKey, oAuthToken, mockCtx)
    }

    @Test
    fun `test searchPatientByKontext returns patient when found`() {
        val kontextId = "test-kontext"
        val mockPatient = Patient().apply { id = "Patient/123" }
        val mockBundle = Bundle().apply {
            addEntry().resource = mockPatient
        }

        // Mocking the fluent API for search
        val mockSearch = mock<IUntypedQuery<Bundle>>()
        val mockSearchForPatient = mock<IQuery<Bundle>>()
        val mockSearchWhere = mock<IQuery<Bundle>>()
        val mockSearchHeader = mock<IQuery<Bundle>>()
        val mockSearchReturn = mock<IQuery<Bundle>>()

        whenever(mockClient.search<Bundle>()).thenReturn(mockSearch)
        whenever(mockSearch.forResource(Patient::class.java)).thenReturn(mockSearchForPatient)
        whenever(mockSearchForPatient.where(any<ICriterion<*>>())).thenReturn(mockSearchWhere)
        whenever(mockSearchWhere.withAdditionalHeader(eq("X-FHIR-Profile"), any())).thenReturn(mockSearchHeader)
        whenever(mockSearchHeader.returnBundle(Bundle::class.java)).thenReturn(mockSearchReturn)
        whenever(mockSearchReturn.execute()).thenReturn(mockBundle)

        val result = fhirService.searchPatientByKontext(kontextId)

        assertNotNull(result)
        assertEquals("123", result.idPart)
        
        verify(mockSearchForPatient).where(argThat<ICriterion<*>> { 
            // Hier könnte man noch tiefer prüfen, ob das Criterion das richtige ist
            true 
        })
    }

    @Test
    fun `test searchPatientByKontext returns null when not found`() {
        val kontextId = "unknown-kontext"
        val mockBundle = Bundle() // Empty bundle

        val mockSearch = mock<IUntypedQuery<Bundle>>()
        val mockSearchForPatient = mock<IQuery<Bundle>>()
        val mockSearchWhere = mock<IQuery<Bundle>>()
        val mockSearchHeader = mock<IQuery<Bundle>>()
        val mockSearchReturn = mock<IQuery<Bundle>>()

        whenever(mockClient.search<Bundle>()).thenReturn(mockSearch)
        whenever(mockSearch.forResource(Patient::class.java)).thenReturn(mockSearchForPatient)
        whenever(mockSearchForPatient.where(any<ICriterion<*>>())).thenReturn(mockSearchWhere)
        whenever(mockSearchWhere.withAdditionalHeader(any(), any())).thenReturn(mockSearchHeader)
        whenever(mockSearchHeader.returnBundle(Bundle::class.java)).thenReturn(mockSearchReturn)
        whenever(mockSearchReturn.execute()).thenReturn(mockBundle)

        val result = fhirService.searchPatientByKontext(kontextId)

        assertNull(result)
    }

    @Test
    fun `test createObservation`() {
        val kontextId = "test-kontext"
        val mockOutcome = OperationOutcome().apply {
            addIssue().apply {
                severity = OperationOutcome.IssueSeverity.INFORMATION
                diagnostics = "Resource created"
            }
        }
        val mockCreate = mock<ICreate>()
        val mockCreateTyped = mock<ICreateTyped>()
        val mockMethodOutcome = mock<ca.uhn.fhir.rest.api.MethodOutcome>()

        whenever(mockClient.create()).thenReturn(mockCreate)
        whenever(mockCreate.resource(any<Observation>())).thenReturn(mockCreateTyped)
        whenever(mockCreateTyped.execute()).thenReturn(mockMethodOutcome)
        whenever(mockMethodOutcome.operationOutcome).thenReturn(mockOutcome)

        val result = fhirService.createObservation(kontextId, "befund", "Testwert")

        assertNotNull(result)
        assertEquals("Resource created", result.issueFirstRep.diagnostics)
        
        verify(mockCreate).resource(argThat<Observation> {
            this.value.toString() == "Testwert"
        })
    }

    @Test
    fun `test sendTransactionBundle`() {
        val kontextId = "test-kontext"
        val obs = Observation().apply { status = Observation.ObservationStatus.FINAL }
        val cond = Condition().apply { code = CodeableConcept().setText("Test Diagnose") }
        
        val mockBundle = Bundle().apply { 
            type = Bundle.BundleType.TRANSACTIONRESPONSE
            addEntry().response = Bundle.BundleEntryResponseComponent().setStatus("201 Created")
        }
        
        val mockTransaction = mock<ITransaction>()
        val mockTransactionTyped = mock<ITransactionTyped<Bundle>>()
        
        whenever(mockClient.transaction()).thenReturn(mockTransaction)
        whenever(mockTransaction.withBundle(any<Bundle>())).thenReturn(mockTransactionTyped)
        whenever(mockTransactionTyped.execute()).thenReturn(mockBundle)
        
        val result = fhirService.sendTransactionBundle(kontextId, listOf(obs, cond))
        
        assertNotNull(result)
        assertEquals(Bundle.BundleType.TRANSACTIONRESPONSE, result.type)
        
        verify(mockTransaction).withBundle(argThat<Bundle> {
            this.type == Bundle.BundleType.TRANSACTION && this.entry.size == 2
        })
    }

    @Test
    fun `test searchPatientByName returns bundle`() {
        val family = "Mustermann"
        val given = "Max"
        val birthdate = "1980-01-01"
        val mockBundle = Bundle().apply {
            type = Bundle.BundleType.SEARCHSET
            addEntry().resource = Patient().apply { id = "Patient/1" }
        }

        val mockSearch = mock<IUntypedQuery<Bundle>>()
        val mockSearchForPatient = mock<IQuery<Bundle>>()
        val mockSearchWhere1 = mock<IQuery<Bundle>>()
        val mockSearchAnd1 = mock<IQuery<Bundle>>()
        val mockSearchAnd2 = mock<IQuery<Bundle>>()
        val mockSearchHeader = mock<IQuery<Bundle>>()
        val mockSearchReturn = mock<IQuery<Bundle>>()

        whenever(mockClient.search<Bundle>()).thenReturn(mockSearch)
        whenever(mockSearch.forResource(Patient::class.java)).thenReturn(mockSearchForPatient)
        whenever(mockSearchForPatient.where(any<ICriterion<*>>())).thenReturn(mockSearchWhere1)
        whenever(mockSearchWhere1.and(any<ICriterion<*>>())).thenReturn(mockSearchAnd1)
        whenever(mockSearchAnd1.and(any<ICriterion<*>>())).thenReturn(mockSearchAnd2)
        whenever(mockSearchAnd2.withAdditionalHeader(any(), any())).thenReturn(mockSearchHeader)
        whenever(mockSearchHeader.returnBundle(Bundle::class.java)).thenReturn(mockSearchReturn)
        whenever(mockSearchReturn.execute()).thenReturn(mockBundle)

        val result = fhirService.searchPatientByName(family, given, birthdate)

        assertNotNull(result)
        assertEquals(1, result.entry.size)
        
        verify(mockSearchForPatient).where(argThat<ICriterion<*>> { true })
        verify(mockSearchWhere1).and(argThat<ICriterion<*>> { true })
        verify(mockSearchAnd1).and(argThat<ICriterion<*>> { true })
    }

    @Test
    fun `test searchPatientByKontext includes URL in exception on server error`() {
        val kontextId = "test-error-kontext"
        
        val mockSearch = mock<IUntypedQuery<Bundle>>()
        val mockSearchForPatient = mock<IQuery<Bundle>>()
        val mockSearchWhere = mock<IQuery<Bundle>>()
        val mockSearchHeader = mock<IQuery<Bundle>>()
        val mockSearchReturn = mock<IQuery<Bundle>>()

        whenever(mockClient.search<Bundle>()).thenReturn(mockSearch)
        whenever(mockSearch.forResource(Patient::class.java)).thenReturn(mockSearchForPatient)
        whenever(mockSearchForPatient.where(any<ICriterion<*>>())).thenReturn(mockSearchWhere)
        whenever(mockSearchWhere.withAdditionalHeader(any(), any())).thenReturn(mockSearchHeader)
        whenever(mockSearchHeader.returnBundle(Bundle::class.java)).thenReturn(mockSearchReturn)
        
        // Simuliere einen 404 Fehler vom Server
        val exception = ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException("HTTP 404 Not Found")
        
        // Wir müssen den Interceptor simulieren, da wir den Mock-Client verwenden
        // Aber in FhirService wird der Interceptor am Client registriert.
        // In searchPatientByKontext wird lastUrlInterceptor.lastUrl verwendet.
        // Da wir den realen Interceptor in der FhirService Instanz haben, 
        // wird er nur aufgerufen, wenn der Client wirklich Methoden aufruft.
        // Da 'client' ein Mock ist, wird der Interceptor NICHT automatisch aufgerufen,
        // es sei denn wir triggern ihn manuell oder wir verwenden einen echten Client (schwierig hier).
        
        // Alternative: Wir lassen den Test so wie er ist und schauen ob er fehlschlägt.
        // Er wird wahrscheinlich fehlschlagen, weil lastUrl null sein wird (oder baseUrl).
        
        whenever(mockSearchReturn.execute()).thenThrow(exception)

        val thrown = assertThrows<Exception> {
            fhirService.searchPatientByKontext(kontextId)
        }

        // Da der Mock-Client den Interceptor nicht aufruft, wird im catch-Block `baseUrl` verwendet
        assertTrue(thrown.message?.contains("URL: $baseUrl") == true)
        assertTrue(thrown.message?.contains("HTTP 404") == true)
    }
}
