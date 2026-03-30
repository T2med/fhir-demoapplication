# Integrationsleitfaden: Nutzung der externen T2med FHIR API für Drittanbieter

Dieser Leitfaden beschreibt den praktischen Integrationsablauf für Drittanbieter gegen die T2med FHIR-API.

Stand: **2026-03-23**

## 1. Zielbild und Grundprinzip

Die Integration ist kontextbasiert:

1. Die Drittanbieter-Software wird durch Nutzerinteraktion aus dem APS-Client heraus per Deep Link aufgerufen. Dafür wird im APS ein temporärer Kontext erstellt.
2. Der Drittanbieter ruft die FHIR-API mit `X-API-Key` auf.
3. Der Drittanbieter übergibt die `kontextId` als FHIR-Identifier in kontextgebundenen Ressourcen.
4. APS ordnet die Daten dem Kontext zu und schreibt sie in die Fachdomäne.

Wichtig:

- Der Kontext-Identifier lautet `system=https://fhir.t2med.de/identifier/kontext`, `value=<kontextId>`.
- Ohne gültigen Kontext können kontextgebundene `create`-Operationen sowie `GET /Patient?identifier=...` nicht verarbeitet werden.
- Die aktuelle Implementierung hält Kontexte bis zu 4 Stunden vor und bereinigt sie stündlich.

## 2. Voraussetzungen

### 2.1 Serverseitig (APS)

- Feature `APS_37950_EXTERNE_FHIR_API_AKTIVIEREN` ist aktiv.
- FHIR-Servlet ist aktiviert und unter `/aps/fhir/api` erreichbar.
- Drittanbieter ist in APS aktiviert.

### 2.2 Drittanbieter-seitig

- HTTPS-Client für FHIR R4 JSON.
- Konfigurierbare Header:
  - `X-API-Key` als Pflichtheader
  - `X-TreatWarningAsError` optional
  - `X-FHIR-Profile` für `Patient`-Read und `Patient`-Search
- Unterstützung für `OperationOutcome`.

## 3. Authentifizierung und Header

Pflichtheader:

- `X-API-Key: <API_KEY>`

Optionale Header:

- `X-TreatWarningAsError: true|false`
  - fehlt oder `true` => Warnungen werden als Fehler behandelt
  - `false` => Warnungen bleiben Warnungen
- `X-FHIR-Profile: <profil-url>|1.0.0`
  - wird nur für `Patient`-Read und `Patient`-Search ausgewertet
  - ohne Header wird `https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0` verwendet

Typische Fehler:

- `403 Forbidden`: API-Key fehlt oder ist ungültig.
- `503 Service Unavailable`: FHIR-API ist per Feature-Flag nicht freigeschaltet.

## 4. Endpunktübersicht (Drittanbietersicht)

Externe FHIR-API:

- Basis: `/aps/fhir/api`
- Ressourcen:
  - `GET /Patient?identifier=<system>|<value>`
  - `GET /Patient?family=...&given=...&birthdate=...`
  - `GET /Patient/{id}`
  - `POST /Observation`
  - `POST /Condition`
  - `POST /Procedure`
  - `POST /DocumentReference`
  - `POST /` für FHIR-Transaction-Bundles

Profilübersicht:

| Ressource | Profil | Unterstützte Operationen |
| --- | --- | --- |
| Patient | `https://fhir.t2med.de/StructureDefinition/FhirApiPatient\|1.0.0` | `read`, `search(identifier)`, `search(family/given/birthdate)` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationAnamnese\|1.0.0` | `create` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationBefund\|1.0.0` | `create` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationFreitext\|1.0.0` | `create` |
| Condition | `https://fhir.t2med.de/StructureDefinition/FhirApiConditionDiagnose\|1.0.0` | `create` |
| Procedure | `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureTherapie\|1.0.0` | `create` |
| Procedure | `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureProcedere\|1.0.0` | `create` |
| DocumentReference | `https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceFreitext\|1.0.0` | `create` |

Kontext-Management und Drittanbieter-Freischaltung erfolgen APS-intern unter `/aps/rest/fhir/api/...`.

## 5. Empfohlener Integrationsablauf (End-to-End)

### 5.1 Schritt 1: Kontext bereitstellen lassen

- Kontext wird über die APS-interne Boundary `/aps/rest/fhir/api/kontext/anlegen` erzeugt.
- Pflichtfelder dort: `patientId`, `arztrolleId`, `behandlungsortId`.
- Die Response liefert `kontext`, `fhirApiBase` und bei Erfolg zusätzlich einen `loginToken`.
- Aus Sicht des Drittanbieters erfolgt der Einstieg typischerweise über einen Deep Link aus dem APS-Client.
- Der Deep Link enthält `kontextId`, `fhirBasisUrl` und `oAuthToken`.
- Der APS-Client befüllt `oAuthToken` aus dem von `/aps/rest/fhir/api/kontext/anlegen` gelieferten `loginToken`.

### 5.2 Schritt 2: Optional Patient laden oder validieren

- Für Kontextvalidierung: `GET /aps/fhir/api/Patient?identifier=https://fhir.t2med.de/identifier/kontext|<KONTEXT_ID>`
- Unterstütztes Header-Profil:
  - `https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0`

### 5.3 Schritt 3: Fachdaten schreiben

Je nach Anwendungsfall:

- `Observation` für Anamnese, Befund oder Freitext
- `Condition` für Diagnosen
- `Procedure` für Therapie oder Prozedere
- `DocumentReference` für Freitext über Attachment

Für alle kontextgebundenen Ressourcen gilt:

- `identifier.system = https://fhir.t2med.de/identifier/kontext`
- `identifier.value = <KONTEXT_ID>`
- `meta.profile[0]` muss ein unterstütztes Profil enthalten

### 5.4 Schritt 4: Optional als Transaction bündeln

- `POST /aps/fhir/api` mit `Bundle.type = transaction`
- Pro Entry ist nur `request.method = POST` implementiert.
- Bei `error` oder `fatal` in einem Entry-`OperationOutcome` wird die Gesamttransaktion auf Rollback markiert.

### 5.5 Schritt 5: Kontextabschluss

- Nach Abschluss kann der Kontext über `/aps/rest/fhir/api/kontext/entfernen` entfernt werden.
- Kontext-IDs nicht wiederverwenden.

## 6. Unterstützte Profile (Kurzfassung)

| Ressource | Profil |
| --- | --- |
| Patient | `https://fhir.t2med.de/StructureDefinition/FhirApiPatient\|1.0.0` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationAnamnese\|1.0.0` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationBefund\|1.0.0` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationFreitext\|1.0.0` |
| Condition | `https://fhir.t2med.de/StructureDefinition/FhirApiConditionDiagnose\|1.0.0` |
| Procedure | `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureTherapie\|1.0.0` |
| Procedure | `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureProcedere\|1.0.0` |
| DocumentReference | `https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceFreitext\|1.0.0` |

## 7. Fehlerbehandlung und Robustheit

Empfehlungen:

- Immer `OperationOutcome` auswerten.
- Diese Statuscodes explizit behandeln:
  - `400`: Request formal ungültig, z. B. Profil fehlt oder Kontext-Identifier fehlt
  - `403`: API-Key fehlt oder ist ungültig
  - `422`: fachliche oder technische Verarbeitung fehlgeschlagen
  - `501`: Profil nicht unterstützt
  - `503`: FHIR-API per Feature-Flag deaktiviert
- Zusätzlich `404` für `Patient`-Read oder `Patient`-Suche per Kontext-Identifier berücksichtigen.
- Retry nur bei transienten technischen Fehlern, nicht bei fachlichen `4xx`.

Implementierungsnahe Besonderheiten:

- `GET /Patient?family=...&given=...&birthdate=...` liefert bei fehlenden Suchparametern aktuell kein `4xx`, sondern ein leeres Suchergebnis.

## 8. Verwendete Code-Systeme (insb. `Condition`)

| Anwendungsfall | System-URL | Codes |
| --- | --- | --- |
| ICD-Diagnose in `Condition.code.coding` | `http://fhir.de/CodeSystem/bfarm/icd-10-gm` | ICD-10-GM-Code in `coding.code`, Version optional in `coding.version` |
| Diagnosesicherheit | `https://fhir.kbv.de/CodeSystem/KBV_CS_SFHIR_ICD_DIAGNOSESICHERHEIT` | `V`, `G`, `A`, `Z` |
| Seitenlokalisation | `https://fhir.kbv.de/CodeSystem/KBV_CS_SFHIR_ICD_SEITENLOKALISATION` | `L`, `R`, `B` |
| Diagnose-Relevanz | `https://fhir.t2med.de/CodeSystem/FhirApiDiagnoseRelevanz` | `akut`, `dauerhaft`, `anamnestisch` |

## 9. Sicherheits- und Betriebsaspekte

- API-Key nie im Klartext loggen.
- TLS erzwingen.
- Kontext-IDs als kurzlebige technische Tokens behandeln.
- `X-TreatWarningAsError` bewusst setzen. Ohne Header ist das Verhalten identisch zu `true`.

## 10. Go-Live-Checkliste

- [ ] Drittanbieter in APS aktiviert
- [ ] API-Key vorhanden und sicher hinterlegt
- [ ] Feature `APS_37950_EXTERNE_FHIR_API_AKTIVIEREN` in der Zielumgebung aktiv
- [ ] Test: `GET /Patient?identifier=https://fhir.t2med.de/identifier/kontext|<KONTEXT_ID>`
- [ ] Test: gewünschte `POST`-Ressourcentypen mit korrektem `meta.profile`
- [ ] Test: Fehlerfall ohne oder mit falschem Profil
- [ ] Optional: Transaction-Verhalten mit Rollback geprüft
- [ ] Monitoring für HTTP-Status und `OperationOutcome` vorhanden

## 11. Schnellstart (Minimalfluss)

1. Kontext über APS bereitstellen oder übernehmen.
2. `GET /aps/fhir/api/Patient?identifier=https://fhir.t2med.de/identifier/kontext|<KONTEXT_ID>` mit `X-API-Key` ausführen.
3. Gewünschte Ressource mit gültigem `meta.profile` und Kontext-Identifier anlegen.
4. `OperationOutcome` und HTTP-Status auswerten.
5. Kontext bei Bedarf entfernen und nicht wiederverwenden.

## Externe API Request-/Response-Beispiele

Hinweise:

- Alle Beispiele verwenden Platzhalter wie `<HOST>`, `<API_KEY>`, `<KONTEXT_ID>`.
- Für FHIR JSON sollten `Content-Type` und `Accept` auf `application/fhir+json` gesetzt werden.
- Bei `create`-Operationen ist `X-FHIR-Profile` nicht erforderlich; maßgeblich ist `meta.profile`.
- Beim Start aus dem APS-Client wird der Drittanbieter typischerweise zunächst über eine URL der Form `<AUFRUF_PLATZHALTER>://?kontextId=<...>&fhirBasisUrl=<...>&oAuthToken=<...>` geöffnet.

### 1. Externe FHIR-HTTP API (`/aps/fhir/api`)

#### 1.1 Patient per Kontext-Identifier suchen

**Request**

```http
GET https://<HOST>/aps/fhir/api/Patient?identifier=https://fhir.t2med.de/identifier/kontext|<KONTEXT_ID>
Accept: application/fhir+json
X-API-Key: <API_KEY>
X-TreatWarningAsError: true
X-FHIR-Profile: https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0
```

**Response (200)**

```json
{
  "resourceType": "Patient",
  "id": "<PATIENT_OBJECT_ID>",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0"
    ]
  },
  "identifier": [
    {
      "system": "https://fhir.t2med.de/identifier/kontext",
      "value": "<KONTEXT_ID>"
    }
  ]
}
```

#### 1.2 Patient per Name und Geburtsdatum suchen

**Request**

```http
GET https://<HOST>/aps/fhir/api/Patient?family=must&given=max&birthdate=1980-04-12
Accept: application/fhir+json
X-API-Key: <API_KEY>
X-FHIR-Profile: https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0
```

**Response (200, Suchergebnis)**

```json
{
  "resourceType": "Bundle",
  "type": "searchset",
  "entry": [
    {
      "resource": {
        "resourceType": "Patient",
        "meta": {
          "profile": [
            "https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0"
          ]
        }
      }
    }
  ]
}
```

#### 1.3 Patient per ID lesen

**Request**

```http
GET https://<HOST>/aps/fhir/api/Patient/<PATIENT_OBJECT_ID>
Accept: application/fhir+json
X-API-Key: <API_KEY>
X-FHIR-Profile: https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0
```

**Response (200)**

```json
{
  "resourceType": "Patient",
  "id": "<PATIENT_OBJECT_ID>",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0"
    ]
  }
}
```

#### 1.4 Observation anlegen (Befund)

**Request**

```http
POST https://<HOST>/aps/fhir/api/Observation
Content-Type: application/fhir+json
Accept: application/fhir+json
X-API-Key: <API_KEY>
X-TreatWarningAsError: true

{
  "resourceType": "Observation",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiObservationBefund|1.0.0"
    ]
  },
  "identifier": [
    {
      "system": "https://fhir.t2med.de/identifier/kontext",
      "value": "<KONTEXT_ID>"
    }
  ],
  "effectiveDateTime": "2026-01-29T08:36:00+01:00",
  "valueString": "leichtes Fieber"
}
```

**Response (201)**

```json
{
  "resourceType": "OperationOutcome",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiOperationOutcome|1.0.0"
    ]
  },
  "issue": [
    {
      "severity": "information",
      "code": "processing"
    }
  ]
}
```

#### 1.5 Observation anlegen (Anamnese)

Wie 1.4, aber Profil:

```text
https://fhir.t2med.de/StructureDefinition/FhirApiObservationAnamnese|1.0.0
```

#### 1.6 Observation anlegen (Freitext)

Wie 1.4, aber Profil:

```text
https://fhir.t2med.de/StructureDefinition/FhirApiObservationFreitext|1.0.0
```

Zusätzlich mögliche Extension:

```json
{
  "extension": [
    {
      "url": "https://fhir.t2med.de/StructureDefinition/FhirApiFreitextKuerzel",
      "valueString": "Info"
    }
  ]
}
```

#### 1.7 DocumentReference anlegen (Freitext)

**Request**

```http
POST https://<HOST>/aps/fhir/api/DocumentReference
Content-Type: application/fhir+json
Accept: application/fhir+json
X-API-Key: <API_KEY>

{
  "resourceType": "DocumentReference",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceFreitext|1.0.0"
    ]
  },
  "identifier": [
    {
      "system": "https://fhir.t2med.de/identifier/kontext",
      "value": "<KONTEXT_ID>"
    }
  ],
  "date": "2026-01-29T08:36:00+01:00",
  "content": [
    {
      "attachment": {
        "contentType": "text/plain;charset=UTF-8",
        "title": "ToDo",
        "data": "RmVobGVyYmVoYW5kbHVuZyB2ZXJiZXNzZXJuLgpGZWhsZXJzaW11bGF0aW9uIGVpbmJhdWVuLg=="
      }
    }
  ]
}
```

**Response**: analog 1.4 (`201` + `OperationOutcome`).

#### 1.8 Procedure anlegen (Therapie)

Wie 1.4, aber Endpoint `POST /Procedure` und Profil:

```text
https://fhir.t2med.de/StructureDefinition/FhirApiProcedureTherapie|1.0.0
```

Zeitpunkt-Reihenfolge in der Implementierung:

1. Extension `https://fhir.t2med.de/StructureDefinition/FhirApiFeststellungszeitpunkt`
2. Extension `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureOccurrence`
3. `performedDateTime`

#### 1.9 Procedure anlegen (Prozedere)

Wie 1.8, aber Profil:

```text
https://fhir.t2med.de/StructureDefinition/FhirApiProcedureProcedere|1.0.0
```

#### 1.10 Condition anlegen (Diagnose)

**Request**

```http
POST https://<HOST>/aps/fhir/api/Condition
Content-Type: application/fhir+json
Accept: application/fhir+json
X-API-Key: <API_KEY>

{
  "resourceType": "Condition",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiConditionDiagnose|1.0.0"
    ]
  },
  "identifier": [
    {
      "system": "https://fhir.t2med.de/identifier/kontext",
      "value": "<KONTEXT_ID>"
    }
  ],
  "code": {
    "coding": [
      {
        "system": "http://fhir.de/CodeSystem/bfarm/icd-10-gm",
        "version": "2026",
        "code": "J06.9"
      }
    ],
    "text": "Diagnoseklartext"
  },
  "onsetDateTime": "2026-01-29T09:14:00+01:00"
}
```

**Response**: analog 1.4 (`201` + `OperationOutcome`).

#### 1.11 Transaction Bundle

**Request**

```http
POST https://<HOST>/aps/fhir/api
Content-Type: application/fhir+json
Accept: application/fhir+json
X-API-Key: <API_KEY>

{
  "resourceType": "Bundle",
  "type": "transaction",
  "entry": [
    {
      "request": {
        "method": "POST",
        "url": "https://<HOST>/aps/fhir/api/Observation"
      },
      "resource": {
        "resourceType": "Observation",
        "meta": {
          "profile": [
            "https://fhir.t2med.de/StructureDefinition/FhirApiObservationBefund|1.0.0"
          ]
        },
        "identifier": [
          {
            "system": "https://fhir.t2med.de/identifier/kontext",
            "value": "<KONTEXT_ID>"
          }
        ],
        "effectiveDateTime": "2026-01-30T08:36:00+01:00",
        "valueString": "leichtes Fieber"
      }
    }
  ]
}
```

**Response (200)**

```json
{
  "resourceType": "Bundle",
  "type": "transaction-response",
  "entry": [
    {
      "response": {
        "status": "201",
        "outcome": {
          "resourceType": "OperationOutcome",
          "issue": [
            {
              "severity": "information",
              "code": "processing"
            }
          ]
        }
      }
    }
  ]
}
```

### 2. Typische Fehlerbeispiele

#### 2.1 Fehlender API-Key (403)

```json
{
  "resourceType": "OperationOutcome",
  "issue": [
    {
      "severity": "error",
      "code": "forbidden",
      "diagnostics": "Der FHIR-API-Key fehlt oder ist ungültig."
    }
  ]
}
```

#### 2.2 Profil fehlt bei Create (400)

```json
{
  "resourceType": "OperationOutcome",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiOperationOutcome|1.0.0"
    ]
  },
  "issue": [
    {
      "severity": "error",
      "code": "invalid",
      "diagnostics": "Das Profil ist nicht angegeben."
    }
  ]
}
```

#### 2.3 Profil nicht unterstützt (501)

```json
{
  "resourceType": "OperationOutcome",
  "issue": [
    {
      "severity": "error",
      "code": "not-supported",
      "diagnostics": "Das Profil wird nicht unterstützt."
    }
  ]
}
```
