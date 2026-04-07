package constants

object FhirConstants {
    // Identifier Systems
    const val IDENTIFIER_SYSTEM_KONTEXT = "https://fhir.t2med.de/identifier/kontext"
    
    // Code Systems
    const val CODE_SYSTEM_ICD_10_GM = "http://fhir.de/CodeSystem/bfarm/icd-10-gm"
    
    // Profiles
    const val PROFILE_PATIENT = "https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0"
    const val PROFILE_OBSERVATION_BEFUND = "https://fhir.t2med.de/StructureDefinition/FhirApiObservationBefund|1.0.0"
    const val PROFILE_OBSERVATION_ANAMNESE = "https://fhir.t2med.de/StructureDefinition/FhirApiObservationAnamnese|1.0.0"
    const val PROFILE_OBSERVATION_FREITEXT = "https://fhir.t2med.de/StructureDefinition/FhirApiObservationFreitext|1.0.0"
    const val PROFILE_CONDITION_DIAGNOSE = "https://fhir.t2med.de/StructureDefinition/FhirApiConditionDiagnose|1.0.0"
    const val PROFILE_PROCEDURE_THERAPIE = "https://fhir.t2med.de/StructureDefinition/FhirApiProcedureTherapie|1.0.0"
    const val PROFILE_PROCEDURE_PROCEDERE = "https://fhir.t2med.de/StructureDefinition/FhirApiProcedureProcedere|1.0.0"
    const val PROFILE_DOCUMENT_REFERENCE_FREITEXT = "https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceFreitext|1.0.0"
    
    // HTTP Headers
    const val HEADER_X_FHIR_PROFILE = "X-FHIR-Profile"
    const val HEADER_X_API_KEY = "X-API-Key"
    const val HEADER_X_TREAT_WARNING_AS_ERROR = "X-TreatWarningAsError"
    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_PREFER = "Prefer"
    const val HEADER_CONTENT_TYPE = "Content-Type"
}

object AppConstants {
    // Logging and Error Messages
    const val ERROR_KONTEXT_ID_MISSING = "Fehler: Keine kontextId vorhanden"
    const val ERROR_FHIR_SERVICE_NOT_INITIALIZED = "Fehler: FhirService nicht initialisiert"
    const val ERROR_API_KEY_MISSING = "Fehler: API-Key fehlt. Setze T2DEMO_API_KEY oder -Dt2demo.apiKey."
    
    // Query Parameters
    const val QUERY_PARAM_KONTEXT_ID = "kontextId"
    const val QUERY_PARAM_FHIR_BASIS_URL = "fhirBasisUrl"
    const val QUERY_PARAM_OAUTH_TOKEN = "oAuthToken"

    // Runtime Configuration
    const val ENV_API_KEY = "T2DEMO_API_KEY"
    const val SYS_PROP_API_KEY = "t2demo.apiKey"
}
