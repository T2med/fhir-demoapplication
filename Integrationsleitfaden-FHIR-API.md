# Integrationsleitfaden: Nutzung der externen T2med FHIR API für Drittanbieter

Dieser Leitfaden beschreibt den praktischen Integrationsablauf für Drittanbieter gegen die T2med FHIR-API.

Stand: **2026-06-04**

## 1. Zielbild und Grundprinzip

Die Integration ist kontextbasiert:

1. Die Drittanbieter-Software wird durch Nutzerinteraktion aus dem APS-Client heraus per Deep Link aufgerufen. Dafür wird im APS ein temporärer Kontext erstellt.
2. Der Drittanbieter ruft die FHIR-API mit `X-API-Key` auf.
3. Der Drittanbieter übergibt die `kontextId` als FHIR-Identifier oder als `Encounter`-Referenz in kontextgebundenen Ressourcen.
4. APS ordnet die Daten dem Kontext zu und schreibt sie in die Fachdomäne.

Wichtig:

| Thema | Vorgabe |
| --- | --- |
| Kontext-Identifier | `system=https://fhir.t2med.de/identifier/kontext`, `value=<kontextId>` |
| Encounter-Referenz | `Encounter/<kontextId>` |
| Kontextdauer | Kontexte werden bis zu 4 Stunden vorgehalten und stündlich bereinigt. |
| Kontextpflicht | Kontextgebundene `create`-Operationen und `GET /Patient?identifier=...` benötigen einen gültigen Kontext. |

## 1a. OAuth Device Flow als alternativer Einstieg (RFC 8628)

Neben dem klassischen Deep-Link-Start mit fertigem Bearer-Token unterstützt die Demoapplikation den OAuth 2.0 Device Flow als eigenständigen Authentifizierungsweg. Dieser Pfad ist besonders relevant für Präsentationen, Schulungen und Integrationstests, bei denen kein APS-Client-Start zur Verfügung steht.

### Ablauf

```
Demoapplikation                 Auth-Server                       Browser (Nutzer)
      |                              |                                   |
      |-- POST /device_authorization -->|                                |
      |<-- device_code, user_code, ------|                               |
      |    verification_uri_complete,    |                               |
      |    expires_in, interval          |                               |
      |                              |                                   |
      |  zeigt user_code + URI ------>|                               Nutzer öffnet URI
      |                              |<---- Nutzer gibt user_code ein ---|
      |                              |                                   |
      |-- POST /token (polling) ----->|                                  |
      |   (Authorization: Basic, grant_type=urn:ietf:params:oauth:...)  |
      |<-- authorization_pending -----| (Nutzer noch nicht autorisiert) |
      |   (warten, erneut pollen)    |                                   |
      |-- POST /token (polling) ----->|                                  |
      |<-- access_token, token_type --|  (Nutzer hat autorisiert)        |
      |                              |                                   |
      | FHIR-Service initialisiert   |                                   |
```

### Konfigurationsparameter

| Parameter | Quelle | Beschreibung |
| --- | --- | --- |
| `deviceAuthUrl` | Abgeleitet aus `fhirBasisUrl` (gleicher Host, Port 16596) oder `device-flow.properties` | URL des Device Authorization Endpoint |
| `tokenUrl` | Abgeleitet aus `fhirBasisUrl` (gleicher Host, Port 16596) oder `device-flow.properties` | URL des Token Endpoint |
| `clientId` | `device-flow.properties` | OAuth Client ID — entspricht der `ClientId` aus der APS-Drittanbieter-Definition (Demoapplikation: `t2demo`) |
| `clientSecret` | Manuelle Eingabe per Paste | Aus APS-Drittanbieter-Einrichtung; nur im Arbeitsspeicher |
| `scope` | `device-flow.properties` | OAuth Scope — aktuell in APS: `t2med/aps/fhir` |

Das Client-Secret wird nie persistiert, geloggt oder automatisch übertragen. Es wird im APS-Einrichtungsprozess für den Drittanbieter-Zugriff in die Zwischenablage gelegt und von dort durch den Nutzer in das Passwortfeld eingefügt.

### Polling-Verhalten

| Server-Antwort | Bedeutung | Verhalten der Demo |
| --- | --- | --- |
| `200 OK` mit `access_token` | Autorisierung erfolgreich | Token übernehmen, Phase 3 anzeigen |
| `authorization_pending` | Nutzer hat noch nicht autorisiert | Warten, erneut pollen |
| `slow_down` | Polling zu häufig | Polling-Intervall um 5 Sekunden erhöhen |
| `expired_token` | Device Code abgelaufen | Fehleranzeige, Neustart erforderlich |
| `access_denied` | Nutzer hat abgelehnt | Fehleranzeige |

### Ergebnis nach erfolgreichem Device Flow

Nach erfolgreichem Token-Erhalt ist der FHIR-Service identisch initialisiert wie beim klassischen Deep-Link-Pfad. Alle FHIR-Operationen stehen unverändert zur Verfügung.

## 2. Voraussetzungen

### 2.1 Serverseitig (APS)

| Voraussetzung | Wert |
| --- | --- |
| FHIR-Servlet | unter `/aps/fhir/api/r4` erreichbar |
| Drittanbieter | in APS aktiviert |

### 2.2 Drittanbieter-seitig

| Voraussetzung | Erwartung |
| --- | --- |
| FHIR-Client | HTTPS-Client für FHIR R4 |
| Lokale HTTPS-Endpunkte | lokale APS-Server-URLs werden unterstützt |
| Lokale Zertifikate | installationsspezifische APS-Server-Zertifikate werden unterstützt |
| Pflichtheader serverseitig | `X-API-Key` |
| Optionale Serverheader | `X-TreatWarningAsError`, `X-FHIR-Profile` |
| Demo-Header | `Authorization`, `Prefer`, `X-TreatWarningAsError`, `Content-Type` |
| Deep-Link-Parameter | `kontextId`, `fhirBasisUrl`, `oAuthToken` |
| OAuth Client ID | aus der APS-Drittanbieter-Definition (`ClientId`); für die Demoapplikation: `t2demo` |
| OAuth Scope | aktuell in APS festgelegt: `t2med/aps/fhir` |
| Fehlerformat | `OperationOutcome` auswerten |

## 3. Authentifizierung und Header

Serverseitig zwingend:

| Header | Pflicht | Bedeutung |
| --- | --- | --- |
| `X-API-Key: <API_KEY>` | ja | API-Key des aktivierten Drittanbieters. |

Vom aktuellen Democlient zusätzlich gesendet:

| Header | Pflicht aus Serversicht | Bedeutung |
| --- | --- | --- |
| `Authorization: Bearer <oAuthToken>` | nein | Wird aus dem Deep-Link-Parameter `oAuthToken` gebildet. |
| `Prefer: return=OperationOutcome` | nein | Democlient erwartet `OperationOutcome` als Create-Ergebnis. |
| `X-TreatWarningAsError: true` | nein | Warnungen werden als Fehler behandelt. |
| `Content-Type: application/fhir+xml; charset=UTF-8` | nein | Aktueller Democlient-Header. Für JSON-Beispiele `application/fhir+json` verwenden. |

Optionale FHIR-API-Header:

| Header | Verhalten |
| --- | --- |
| `X-TreatWarningAsError: true\|false` | fehlt oder `true` => Warnungen werden als Fehler behandelt; `false` => Warnungen bleiben Warnungen. |
| `X-FHIR-Profile: <profil-url>\|1.0.0` | wird nur für `Patient`-Read und `Patient`-Search ausgewertet; ohne Header wird `FhirApiPatient` verwendet. |
| `Accept: application/fhir+json` | sinnvoll für JSON-Antworten; wird in den JSON-Beispielen verwendet. |

Wichtig:

| Thema | Erklärung |
| --- | --- |
| `loginToken` | Wird von `/aps/rest/fhir/api/kontext/anlegen` geliefert, wenn das aktivierte Produkt eine `clientId` hat. |
| `oAuthToken` | Parametername im Deep Link; enthält das `loginToken` aus der Kontext-Boundary. |
| Leerer Token | Wenn kein `loginToken` erzeugt wird, ersetzt der APS-Client `${oAuthToken}` durch einen leeren Wert. |
| `fhirBasisUrl` | Muss unverändert als Basis-URL des FHIR-Clients verwendet werden. |

Typische Fehler:

| HTTP | Bedeutung |
| --- | --- |
| `403 Forbidden` | API-Key fehlt oder ist ungültig. |
| `503 Service Unavailable` | FHIR-API ist per Feature-Flag nicht freigeschaltet. |

Hinweise aus der Kontext-Anlage:

| Fall | Verhalten |
| --- | --- |
| Unbekanntes Produkt | Kontext-Anlage liefert Validierungsfehler. |
| Nicht aktiviertes Produkt | Kontext-Anlage liefert Validierungsfehler. |
| Aktiviertes Produkt ohne Auth-Registrierung | Kontext wird angelegt, `loginToken` bleibt leer, es wird ein Info-Hinweis zurückgegeben. |

## 4. Endpunktübersicht (Drittanbietersicht)

Externe FHIR-API:

| Thema | Wert |
| --- | --- |
| Basis | `/aps/fhir/api/r4` |
| Format in Beispielen | FHIR JSON |
| Demo-Formatheader | `application/fhir+xml; charset=UTF-8` |

Ressourcen:

| HTTP | Pfad | Unterstützte Operation |
| --- | --- | --- |
| `POST` | `/Patient` | Patient anlegen |
| `GET` | `/Patient?identifier=<system>\|<value>` | Patient per Kontext-Identifier suchen |
| `GET` | `/Patient?family=...&given=...&birthdate=...` | Patient per Name/Geburtsdatum suchen |
| `GET` | `/Patient/{id}` | Patient lesen |
| `PUT` | `/Patient/{id}` | Patient aktualisieren |
| `GET` | `/Encounter/{id}` | Encounter aus Kontext lesen |
| `GET` | `/Organization/{id}` | Organisation lesen |
| `GET` | `/Organization?name=...&identifier=...` | Organisation nach Name/BSNR suchen |
| `GET` | `/Organization?practitioner=...` | Organisationen zu Practitioner suchen |
| `GET` | `/Practitioner/{id}` | Practitioner lesen |
| `GET` | `/Practitioner?name=...&identifier=...` | Practitioner nach Name/LANR suchen |
| `GET` | `/Organization/{id}/Practitioner` | implementierungsnaher Compartment-Sonderfall; Handler liefert aktuell Organisationen zur übergebenen Arztrollen-ID |
| `POST` | `/Observation` | Observation anlegen |
| `POST` | `/Befund` | Custom Resource `Befund` anlegen |
| `POST` | `/Condition` | Diagnose anlegen |
| `POST` | `/Procedure` | Therapie oder Prozedere anlegen |
| `POST` | `/DocumentReference` | Freitext oder Anhang anlegen |
| `POST` | `/` | FHIR-Transaction-Bundle |

Profilübersicht:

| Ressource | Profil | Unterstützte Operationen |
| --- | --- | --- |
| Patient | `https://fhir.t2med.de/StructureDefinition/FhirApiPatient\|1.0.0` | `create`, `read`, `update`, `search(identifier)`, `search(family/given/birthdate)` |
| Encounter | `https://fhir.t2med.de/StructureDefinition/FhirApiEncounter\|1.0.0` | `read` |
| Organization | `https://fhir.t2med.de/StructureDefinition/FhirApiOrganization\|1.0.0` | `read`, `search(name/identifier)`, `search(practitioner)` |
| Practitioner | `https://fhir.t2med.de/StructureDefinition/FhirApiPractitioner\|1.0.0` | `read`, `search(name/identifier)`, registrierter `Organization`-Compartment-Sonderfall |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationAnamnese\|1.0.0` | `create` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationBefund\|1.0.0` | `create` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationFreitext\|1.0.0` | `create` |
| Befund | `https://fhir.t2med.de/StructureDefinition/FhirApiBefund\|1.0.0` | `create` |
| Condition | `https://fhir.t2med.de/StructureDefinition/FhirApiConditionDiagnose\|1.0.0` | `create` |
| Procedure | `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureTherapie\|1.0.0` | `create` |
| Procedure | `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureProcedere\|1.0.0` | `create` |
| DocumentReference | `https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceFreitext\|1.0.0` | `create` |
| DocumentReference | `https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceAnhang\|1.0.0` | `create` |

Kontext-Management und Drittanbieter-Freischaltung erfolgen APS-intern unter `/aps/rest/fhir/api/...`.

## 5. Empfohlener Integrationsablauf (End-to-End)

### 5.1 Schritt 1: Kontext bereitstellen lassen

| Schritt | Beschreibung |
| --- | --- |
| Kontextanlage | APS ruft intern `/aps/rest/fhir/api/kontext/anlegen` auf. |
| Pflichtfelder | `hersteller`, `produkt`, `patientId`, `arztrolleId`, `behandlungsortId` |
| Optionales Feld | `behandlungsfallId` |
| Response | `kontext`, `aufrufUrlTemplate`, `fhirApiBase`, abhängig vom Produkt `loginToken` |
| Deep Link | APS-Client ersetzt `${kontextId}`, `${fhirBasisUrl}`, `${oAuthToken}` im `aufrufUrlTemplate`. |
| `oAuthToken` | Der APS-Client befüllt den Parameter aus dem `loginToken`. |
| `fhirBasisUrl` | Zusammengesetzt aus APS-Basis-URL und `fhirApiBase`; unverändert im Drittanbieter-Client verwenden. |

Beispiel für ein Template:

```text
t2demo://?kontextId=${kontextId}&fhirBasisUrl=${fhirBasisUrl}&oAuthToken=${oAuthToken}
```

### 5.2 Schritt 2: Optional Patient laden oder validieren

| Zweck | Request |
| --- | --- |
| Kontextvalidierung | `GET /aps/fhir/api/r4/Patient?identifier=https://fhir.t2med.de/identifier/kontext\|<KONTEXT_ID>` |
| Header-Profil | `X-FHIR-Profile: https://fhir.t2med.de/StructureDefinition/FhirApiPatient\|1.0.0` |

### 5.3 Schritt 3: Fachdaten schreiben

Je nach Anwendungsfall:

| Ressource | Anwendungsfall |
| --- | --- |
| `Observation` | Anamnese, Befund oder Freitext |
| `Befund` | Custom Befund-Ressource |
| `Condition` | Diagnosen |
| `Procedure` | Therapie oder Prozedere |
| `DocumentReference` | Freitext oder eingebetteter Anhang/Datei-Upload |
| `Patient` | Patient anlegen oder aktualisieren |

Für kontextgebundene Ressourcen gilt:

| Variante | FHIR-Element |
| --- | --- |
| Kontext-Identifier | `identifier.system = https://fhir.t2med.de/identifier/kontext`, `identifier.value = <KONTEXT_ID>` |
| Encounter-Referenz | `encounter.reference = Encounter/<KONTEXT_ID>` |
| Profil | `meta.profile[0]` muss ein unterstütztes Profil enthalten |

Für eine enthaltene `Encounter`-Ressource gelten diese Referenz-Prefixe:

| Kontextbestandteil | Referenz |
| --- | --- |
| Patient | `Patient/<patientObjectId>` |
| Behandlungsfall | `EpisodeOfCare/<behandlungsfallObjectId>` |
| Arztrolle | `Practitioner/<arztrolleObjectId>` |
| Behandlungsort | aktueller Implementierungsstand: `Organisation/<behandlungsortObjectId>` |

Für `DocumentReference` mit Profil `FhirApiDocumentReferenceAnhang|1.0.0` gilt zusätzlich:

| Element | Vorgabe |
| --- | --- |
| `description` | Pflicht |
| `content[0].attachment.data` | Pflicht, Base64-kodierter Anhang |
| `content[0].attachment.contentType` | Pflicht |
| `content[0].attachment.title` | optional |
| Zeitpunkt | bevorzugt `content[0].attachment.creation`, ersatzweise `DocumentReference.date` |
| Kürzel | optional über `https://fhir.t2med.de/StructureDefinition/FhirApiAnhangKuerzel` |

### 5.4 Schritt 4: Optional als Transaction bündeln

| Thema | Vorgabe |
| --- | --- |
| Endpoint | `POST /aps/fhir/api/r4` |
| Bundle | `Bundle.type = transaction` |
| Entry-Methode | nur `request.method = POST` implementiert |
| Entry-URL | Democlient nutzt relative Resource-Namen wie `Observation` oder `Condition` |
| Rollback | Bei `error` oder `fatal` in einem Entry-`OperationOutcome` wird die Gesamttransaktion auf Rollback markiert. |

### 5.5 Schritt 5: Kontextabschluss

| Schritt | Beschreibung |
| --- | --- |
| Entfernen | Kontext kann über `/aps/rest/fhir/api/kontext/entfernen` entfernt werden. |
| Wiederverwendung | Kontext-IDs nicht wiederverwenden. |

## 6. Unterstützte Profile (Kurzfassung)

| Ressource | Profil |
| --- | --- |
| Patient | `https://fhir.t2med.de/StructureDefinition/FhirApiPatient\|1.0.0` |
| Encounter | `https://fhir.t2med.de/StructureDefinition/FhirApiEncounter\|1.0.0` |
| Organization | `https://fhir.t2med.de/StructureDefinition/FhirApiOrganization\|1.0.0` |
| Practitioner | `https://fhir.t2med.de/StructureDefinition/FhirApiPractitioner\|1.0.0` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationAnamnese\|1.0.0` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationBefund\|1.0.0` |
| Observation | `https://fhir.t2med.de/StructureDefinition/FhirApiObservationFreitext\|1.0.0` |
| Befund | `https://fhir.t2med.de/StructureDefinition/FhirApiBefund\|1.0.0` |
| Condition | `https://fhir.t2med.de/StructureDefinition/FhirApiConditionDiagnose\|1.0.0` |
| Procedure | `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureTherapie\|1.0.0` |
| Procedure | `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureProcedere\|1.0.0` |
| DocumentReference | `https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceFreitext\|1.0.0` |
| DocumentReference | `https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceAnhang\|1.0.0` |

## 7. Fehlerbehandlung und Robustheit

Empfehlungen:

| Empfehlung | Begründung |
| --- | --- |
| `OperationOutcome` immer auswerten | Fachliche Fehler und Warnungen werden darüber transportiert. |
| `4xx` nicht automatisch wiederholen | Meist fachliche oder formale Fehler. |
| Transiente technische Fehler gezielt wiederholen | Dafür `X-Request-Id` stabil halten, wenn verwendet. |

Statuscodes:

| HTTP | Bedeutung |
| --- | --- |
| `400` | Request formal ungültig, z. B. Profil fehlt oder Kontext-Identifier ist ungültig |
| `403` | API-Key fehlt oder ist ungültig; beim Demo-API-Key auch: Limit von 100 Aufrufen pro Serverprozess überschritten |
| `404` | Resource bei `read`, `search(identifier)` oder `update` nicht gefunden |
| `409` | Versionskonflikt bei `Patient`-Update |
| `422` | fachliche oder technische Verarbeitung fehlgeschlagen |
| `501` | Profil nicht unterstützt |
| `503` | FHIR-API per Feature-Flag deaktiviert |

Implementierungsnahe Besonderheiten:

| Fall | Verhalten |
| --- | --- |
| `GET /Patient?family=...&given=...&birthdate=...` ohne Parameter | Suchservice wird mit leeren Spezifikationen aufgerufen; die Antwort ist eine Suchmenge. |
| `DocumentReference` mit Profil `FhirApiDocumentReferenceAnhang\|1.0.0` | Validierungsfehler, wenn `description`, `attachment.data` oder `attachment.contentType` fehlen. |
| `GET /Organization/{id}/Practitioner` | Endpoint ist registriert, der aktuelle Handler interpretiert `{id}` aber als Practitioner-/Arztrollen-ID und gibt `Organization`-Ressourcen zurück. Für Drittanbieter ist `GET /Organization?practitioner=<id>` die fachlich klarere Variante. |
| `PUT /Patient/{id}` vs. `PUT /Patient/{id}/_history/{version}` | Der Versionskonflikt-Check (→ 409) wird ausschließlich durch die URL-Form ausgelöst. `PUT /Patient/{id}` überspringt den Check; `PUT /Patient/{id}/_history/{version}` löst ihn aus. Clients sollten daher immer die versionslose URL verwenden, sofern kein bewusster Versionsschutz gewünscht ist. |
| `meta.versionId` im Patient-Response | Der Server liefert `meta.versionId` als vollen Pfad (`{id}/_history/{version}`), nicht als kurze Versionsnummer. Dieser Wert ist nicht direkt als `If-Match`-Header verwendbar. |
| Warnungen | Ohne `X-TreatWarningAsError: false` werden Warnungen als Fehler behandelt. |
| Demo-Content-Type | Demo sendet XML-Content-Type; JSON-Beispiele müssen JSON-Content-Type setzen. |

## 8. Verwendete Code-Systeme (insb. `Condition`)

| Anwendungsfall | System-URL | Codes |
| --- | --- | --- |
| ICD-Diagnose in `Condition.code.coding` | `http://fhir.de/CodeSystem/bfarm/icd-10-gm` | ICD-10-GM-Code in `coding.code`, Version optional in `coding.version` |
| Diagnosesicherheit | `https://fhir.kbv.de/CodeSystem/KBV_CS_SFHIR_ICD_DIAGNOSESICHERHEIT` | `V`, `G`, `A`, `Z` |
| Seitenlokalisation | `https://fhir.kbv.de/CodeSystem/KBV_CS_SFHIR_ICD_SEITENLOKALISATION` | `L`, `R`, `B` |
| Diagnose-Relevanz | `https://fhir.t2med.de/CodeSystem/FhirApiDiagnoseRelevanz` | `akut`, `dauerhaft`, `anamnestisch` |

## 9. Kontaktdaten im Patient-Profil (Telefonnummer und Email)

Die Patient-Ressource enthält Kontaktdaten (Telefonnummern und E-Mail-Adressen) als FHIR `telecom`-Einträge.

### 9.1 Telefonnummer

| Feld | FHIR-Mapping | Mögliche Werte |
| --- | --- | --- |
| Nummer | `telecom.value` | beliebige Zeichenkette |
| Typ | `telecom.system` | `phone` (Telefon), `fax` (Fax) |
| Verwendung | `telecom.use` | `home`, `work`, `mobile` (nur bei `system=phone`) |
| Kategorie | Extension `https://fhir.t2med.de/StructureDefinition/FhirApiTelefonnummerKategorie` | Code-Wert als `valueCode` |
| Kommentar | Extension `https://fhir.t2med.de/StructureDefinition/FhirApiKontaktinformationKommentar` | Freitext als `valueString` |

Beispiel:

```json
{
  "system": "phone",
  "value": "089 123456",
  "use": "home",
  "extension": [
    {
      "url": "https://fhir.t2med.de/StructureDefinition/FhirApiTelefonnummerKategorie",
      "valueCode": "privat"
    },
    {
      "url": "https://fhir.t2med.de/StructureDefinition/FhirApiKontaktinformationKommentar",
      "valueString": "nur vormittags erreichbar"
    }
  ]
}
```

### 9.2 E-Mail-Adresse

| Feld | FHIR-Mapping | Mögliche Werte |
| --- | --- | --- |
| Adresse | `telecom.value` | E-Mail-Adresse |
| Typ | `telecom.system` | immer `email` |
| Verwendung | `telecom.use` | `home`, `work` |
| Kommentar | Extension `https://fhir.t2med.de/StructureDefinition/FhirApiKontaktinformationKommentar` | Freitext als `valueString` |

Beispiel:

```json
{
  "system": "email",
  "value": "max.mustermann@example.de",
  "use": "home",
  "extension": [
    {
      "url": "https://fhir.t2med.de/StructureDefinition/FhirApiKontaktinformationKommentar",
      "valueString": "bevorzugte Kontaktmethode"
    }
  ]
}
```

### 9.3 Verhalten bei leeren Kontaktdaten

Sind keine Telefonnummern oder E-Mail-Adressen hinterlegt, enthält die Patient-Ressource kein `telecom`-Array.

### 9.4 Geschlecht (gender)

Einfache FHIR-Werte (`male`, `female`, `unknown`) werden direkt in `Patient.gender` übertragen. Die deutschen Werte **divers** und **unbestimmt** erfordern zusätzlich die Extension `gender-amtlich-de` auf dem `gender`-Element:

| APS-Wert | `gender` | Extension Code |
| --- | --- | --- |
| männlich | `male` | — |
| weiblich | `female` | — |
| divers | `other` | `D` |
| unbestimmt | `other` | `X` |
| unbekannt | `unknown` | — |

Die Extension wird als `valueCoding` auf dem primitiven `gender`-Element kodiert (nicht auf der Patient-Ressource selbst):

```json
{
  "resourceType": "Patient",
  "gender": "other",
  "_gender": {
    "extension": [
      {
        "url": "http://fhir.de/StructureDefinition/gender-amtlich-de",
        "valueCoding": {
          "system": "http://fhir.de/CodeSystem/gender-amtlich-de",
          "code": "D"
        }
      }
    ]
  }
}
```

Ohne die Extension wird `other` serverseitig als `unbekannt` interpretiert.

## 10. Sicherheits- und Betriebsaspekte

| Thema | Vorgabe |
| --- | --- |
| API-Key | nie im Klartext loggen |
| Transport | FHIR-Aufrufe über TLS durchführen |
| Lokaler APS-Server | FHIR-API wird lokal über `https://` bereitgestellt |
| Zertifikat | APS-Server-Zertifikat wird installationsspezifisch erzeugt |
| Öffentliche CA | Drittanbieter dürfen keine öffentliche CA-signierte Zertifikatskette voraussetzen |
| HTTPS-Client | Bei `fhirBasisUrl` mit `https://` eigenen SSL-Kontext bzw. HTTP-Client konfigurieren |
| Lokale Hosts | `localhost`, `127.0.0.1` und lokal konfigurierte Hostnamen unterstützen |
| Demo-Verhalten | Demo nutzt einen dynamischen SSL-Kontext und akzeptiert lokale Zertifikate; produktiv sollte der Trust-Store verwaltet werden |
| Kontext-ID | als kurzlebiges technisches Token behandeln |
| Warnungen | `X-TreatWarningAsError` bewusst setzen |
| Demo-API-Key-Limit | Bei Nutzung des Demo-API-Keys (t2demo-Client) sind maximal 100 API-Aufrufe pro Serverprozess möglich. Nach Erreichen des Limits liefert jeder weitere Aufruf `403 Forbidden`. Der Zähler wird nicht zurückgesetzt — für weitere Tests APS neu starten. |

## 11. Go-Live-Checkliste

**Klassischer Deep-Link-Pfad:**
- [ ] Drittanbieter in APS aktiviert
- [ ] API-Key vorhanden und sicher hinterlegt
- [ ] `drittanbieterKey` bzw. Hersteller/Produkt korrekt konfiguriert
- [ ] Verarbeitung der Deep-Link-Parameter `kontextId`, `fhirBasisUrl`, `oAuthToken` implementiert
- [ ] `fhirBasisUrl` unverändert als FHIR-Client-Basis-URL verwendet
- [ ] Eigener SSL-Kontext/HTTP-Client für lokale `https://`-APS-Server eingerichtet
- [ ] Test mit installationsspezifischem lokalem APS-Zertifikat durchgeführt
- [ ] Test mit `localhost`, `127.0.0.1` oder lokal konfiguriertem Hostnamen durchgeführt
- [ ] Test: Aufruf durch T2med-Client über Deep Link
- [ ] Test: `GET /Patient?identifier=https://fhir.t2med.de/identifier/kontext|<KONTEXT_ID>`
- [ ] Test: gewünschte `POST`-Ressourcentypen mit korrektem `meta.profile`
- [ ] Test: `DocumentReference` mit Profil `FhirApiDocumentReferenceAnhang|1.0.0`
- [ ] Test: Fehlerfall ohne oder mit falschem Profil
- [ ] Optional: Transaction-Verhalten mit Rollback geprüft
- [ ] Monitoring für HTTP-Status und `OperationOutcome` vorhanden

**Zusätzlich bei OAuth Device Flow (Variante B):**
- [ ] OAuth Device Authorization Endpoint und Token Endpoint bekannt und erreichbar
- [ ] Client Secret aus APS-Drittanbieter-Einrichtung bereitgestellt
- [ ] Client Secret wird ausschließlich im Arbeitsspeicher gehalten, nie persistiert oder geloggt
- [ ] Polling-Verhalten für `authorization_pending`, `slow_down`, `expired_token` und `access_denied` implementiert
- [ ] Polling-Intervall bei `slow_down` korrekt erhöht (mindestens +5 Sekunden)
- [ ] Device Code Expiry korrekt behandelt (Neustart erforderlich)
- [ ] Test: kompletter Device Flow mit Autorisierung im Browser
- [ ] Test: Abbruch und erneuter Start des Device Flow

## 12. Schnellstart (Minimalfluss)

### Variante A: Klassischer Deep-Link-Pfad

1. Deep Link auswerten und `kontextId`, `fhirBasisUrl`, `oAuthToken` übernehmen.
2. HTTPS-Client für `fhirBasisUrl` mit passender SSL-Konfiguration initialisieren.
3. `X-API-Key` bei jedem FHIR-Aufruf mitsenden.
4. Optional wie die Demo `Authorization: Bearer <oAuthToken>`, `Prefer: return=OperationOutcome` und `X-TreatWarningAsError: true` mitsenden.
5. `GET /Patient?identifier=https://fhir.t2med.de/identifier/kontext|<KONTEXT_ID>` ausführen.
6. Gewünschte Ressource mit gültigem `meta.profile` und Kontext-Identifier oder Encounter-Referenz anlegen.
7. `OperationOutcome` und HTTP-Status auswerten.
8. Kontext bei Bedarf entfernen und nicht wiederverwenden.

### Variante B: OAuth Device Flow

1. `POST <deviceAuthUrl>` mit `client_id`, `client_secret` und `scope` im Body (Form-Encoded).
2. `device_code`, `user_code`, `verification_uri_complete` (Fallback: `verification_uri`), `expires_in` und `interval` aus der Response lesen.
3. `user_code` und `verification_uri_complete` dem Nutzer anzeigen.
4. Polling: `POST <tokenUrl>` mit `grant_type=urn:ietf:params:oauth:grant-type:device_code`, `device_code`, `client_id`, `client_secret` — alle `interval` Sekunden.
5. Bei `authorization_pending`: warten und erneut pollen. Bei `slow_down`: Intervall um 5 Sekunden erhöhen.
6. Bei `200 OK` mit `access_token`: Token übernehmen, FHIR-Client wie in Variante A initialisieren.
7. Ab hier identisch mit Variante A: FHIR-Operationen mit `Authorization: Bearer <access_token>` ausführen.

## Externe API Request-/Response-Beispiele

Hinweise:

| Thema | Hinweis |
| --- | --- |
| Platzhalter | Beispiele verwenden `<HOST>`, `<API_KEY>`, `<KONTEXT_ID>`, `<OAUTH_TOKEN>`. |
| JSON | Für JSON `Content-Type` und `Accept` auf `application/fhir+json` setzen. |
| Demo | Der aktuelle Democlient setzt global `Content-Type: application/fhir+xml; charset=UTF-8`. |
| `X-FHIR-Profile` | Bei `create`-Operationen nicht erforderlich; maßgeblich ist `meta.profile`. |
| Deep Link | Der APS-Client öffnet das `aufrufUrlTemplate` nach Ersetzung von `${kontextId}`, `${fhirBasisUrl}`, `${oAuthToken}`. |

### 1. Externe FHIR-HTTP API (`/aps/fhir/api/r4`)

#### 1.1 Patient per Kontext-Identifier suchen

**Request**

```http
GET https://<HOST>/aps/fhir/api/r4/Patient?identifier=https://fhir.t2med.de/identifier/kontext|<KONTEXT_ID>
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
Prefer: return=OperationOutcome
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
GET https://<HOST>/aps/fhir/api/r4/Patient?family=must&given=max&birthdate=1980-04-12
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
Prefer: return=OperationOutcome
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
GET https://<HOST>/aps/fhir/api/r4/Patient/<PATIENT_OBJECT_ID>
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
Prefer: return=OperationOutcome
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

#### 1.4 Patient anlegen

**Request**

```http
POST https://<HOST>/aps/fhir/api/r4/Patient
Content-Type: application/fhir+json
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
X-API-Key: <API_KEY>

{
  "resourceType": "Patient",
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
  ],
  "name": [
    {
      "family": "Mustermann",
      "given": ["Max"]
    }
  ],
  "birthDate": "1980-04-12",
  "gender": "male"
}
```

**Response**: analog 1.6 (`201` + `OperationOutcome`).

#### 1.5 Patient aktualisieren

**Request** — versionslose URL verwenden (kein `/_history/`), um unbeabsichtigte 409-Fehler zu vermeiden:

```http
PUT https://<HOST>/aps/fhir/api/r4/Patient/<PATIENT_OBJECT_ID>
Content-Type: application/fhir+json
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
X-API-Key: <API_KEY>

{
  "resourceType": "Patient",
  "id": "<PATIENT_OBJECT_ID>",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0"
    ]
  },
  "name": [
    {
      "family": "Mustermann",
      "given": ["Max"]
    }
  ],
  "birthDate": "1980-04-12",
  "gender": "other",
  "_gender": {
    "extension": [
      {
        "url": "http://fhir.de/StructureDefinition/gender-amtlich-de",
        "valueCoding": {
          "system": "http://fhir.de/CodeSystem/gender-amtlich-de",
          "code": "D"
        }
      }
    ]
  },
  "telecom": [
    {
      "system": "phone",
      "value": "089 123456",
      "use": "home"
    }
  ]
}
```

**Response**: analog 1.7 (`201` + `OperationOutcome`).

#### 1.6 Observation anlegen (Befund)

**Request**

```http
POST https://<HOST>/aps/fhir/api/r4/Observation
Content-Type: application/fhir+json
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
Prefer: return=OperationOutcome
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
  "status": "final",
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

#### 1.7 Observation anlegen (Anamnese)

Wie 1.4, aber Profil:

```text
https://fhir.t2med.de/StructureDefinition/FhirApiObservationAnamnese|1.0.0
```

#### 1.8 Observation anlegen (Freitext)

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

#### 1.9 DocumentReference anlegen (Freitext)

**Request**

```http
POST https://<HOST>/aps/fhir/api/r4/DocumentReference
Content-Type: application/fhir+json
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
Prefer: return=OperationOutcome
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
  "status": "current",
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

**Response**: analog 1.6 (`201` + `OperationOutcome`).

#### 1.10 DocumentReference anlegen (Anhang)

**Request**

```http
POST https://<HOST>/aps/fhir/api/r4/DocumentReference
Content-Type: application/fhir+json
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
Prefer: return=OperationOutcome
X-API-Key: <API_KEY>

{
  "resourceType": "DocumentReference",
  "meta": {
    "profile": [
      "https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceAnhang|1.0.0"
    ]
  },
  "identifier": [
    {
      "system": "https://fhir.t2med.de/identifier/kontext",
      "value": "<KONTEXT_ID>"
    }
  ],
  "status": "current",
  "date": "2026-01-29T08:36:00+01:00",
  "description": "Befundbericht als PDF",
  "extension": [
    {
      "url": "https://fhir.t2med.de/StructureDefinition/FhirApiAnhangKuerzel",
      "valueString": "PDF"
    }
  ],
  "content": [
    {
      "attachment": {
        "contentType": "application/pdf",
        "title": "befundbericht.pdf",
        "creation": "2026-01-29T08:36:00+01:00",
        "data": "JVBERi0xLjQKJ..."
      }
    }
  ]
}
```

**Response**: analog 1.6 (`201` + `OperationOutcome`).

#### 1.11 Procedure anlegen (Therapie)

Wie 1.4, aber Endpoint `POST /Procedure` und Profil:

```text
https://fhir.t2med.de/StructureDefinition/FhirApiProcedureTherapie|1.0.0
```

Zeitpunkt-Reihenfolge in der Implementierung:

1. Extension `https://fhir.t2med.de/StructureDefinition/FhirApiFeststellungszeitpunkt`
2. Extension `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureOccurrence`
3. `performedDateTime`

#### 1.12 Procedure anlegen (Prozedere)

Wie 1.9, aber Profil:

```text
https://fhir.t2med.de/StructureDefinition/FhirApiProcedureProcedere|1.0.0
```

#### 1.13 Condition anlegen (Diagnose)

**Request**

```http
POST https://<HOST>/aps/fhir/api/r4/Condition
Content-Type: application/fhir+json
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
Prefer: return=OperationOutcome
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

**Response**: analog 1.6 (`201` + `OperationOutcome`).

#### 1.14 Transaction Bundle

**Request**

```http
POST https://<HOST>/aps/fhir/api/r4
Content-Type: application/fhir+json
Accept: application/fhir+json
Authorization: Bearer <OAUTH_TOKEN>
Prefer: return=OperationOutcome
X-API-Key: <API_KEY>

{
  "resourceType": "Bundle",
  "type": "transaction",
  "entry": [
    {
      "request": {
        "method": "POST",
        "url": "Observation"
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
        "status": "final",
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
