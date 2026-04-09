# T2med FHIR-API-Demo

Diese Demoapplikation ist eine Desktop-Referenz für die Deep-Link-basierte Anbindung eines Drittanbieters an die externe T2med-FHIR-API. Sie verarbeitet einen `T2demo://`-Aufruf, extrahiert den API-Kontext aus der URL und stellt anschließend typische FHIR-Lese- und Schreiboperationen gegen einen APS-FHIR-Endpunkt bereit.

Die README beschreibt den aktuellen Implementierungsstand der Demo. Fachliche Integrationsdetails und HTTP-Beispiele stehen zusätzlich im [Integrationsleitfaden-FHIR-API.md](./Integrationsleitfaden-FHIR-API.md).

## Was die Demo aktuell kann

### Deep-Link-Verarbeitung

Die App startet über ein Custom-URL-Scheme und wertet diese Query-Parameter aus:

- `kontextId`
- `fhirBasisUrl`
- `oAuthToken`

Die URL wird in der GUI angezeigt. Protokoll, Host, Pfad und alle Query-Parameter werden sichtbar gemacht. Wenn `fhirBasisUrl` und `oAuthToken` vorhanden sind, initialisiert die App automatisch einen `FhirService`.

Beispiel:

```text
T2demo://demo/start?kontextId=<KONTEXT_ID>&fhirBasisUrl=https%3A%2F%2F127.0.0.1%3A16567%2Faps%2Ffhir%2Fapi&oAuthToken=<OAUTH_TOKEN>
```

### GUI-Demoaktionen

Die aktuelle Oberfläche bietet Buttons für diese FHIR-Aktionen:

- Patient über Kontext-Identifier suchen
- `Observation` mit Profil `Befund` anlegen
- `Condition` für eine Diagnose anlegen
- `Procedure` mit Profil `Therapie` anlegen
- `DocumentReference` für Freitext anlegen
- FHIR-Transaction-Bundle mit `Observation` und `Condition` senden

### Bereits implementierte, bisher kaum dokumentierte Anwendungsfälle

Zusätzlich zur GUI sind im `FhirService` weitere Fälle implementiert und über Tests abgedeckt:

- Patient per Name, Vorname und Geburtsdatum suchen
- Patient per logischer FHIR-ID lesen
- `Observation` in den Typen `befund`, `anamnese`, `freitext` anlegen
- `Procedure` in den Typen `therapie`, `prozedere` anlegen
- Ressourcen als FHIR-Transaction-Bundle gebündelt anlegen

## Technischer Ablauf

1. APS startet den Drittanbieter über einen Deep Link.
2. Die Demo extrahiert `kontextId`, `fhirBasisUrl` und `oAuthToken`.
3. Für `https://`-Basis-URLs wird ein eigener HTTP-/SSL-Client konfiguriert, der auch installationsspezifische lokale Zertifikate akzeptiert.
4. Die Demo sendet FHIR-R4-Requests an `/aps/fhir/api`.
5. Kontextgebundene Ressourcen erhalten automatisch den Identifier `https://fhir.t2med.de/identifier/kontext|<KONTEXT_ID>`.

## FHIR-Konfiguration

### Erwartete Header

Die Demo setzt für Requests diese Header:

- `Authorization: Bearer <oAuthToken>`
- `Prefer: return=OperationOutcome`
- `X-API-Key: <API_KEY>`
- `X-TreatWarningAsError: true`

Für `Patient`-Read und `Patient`-Search wird zusätzlich gesetzt:

- `X-FHIR-Profile: https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0`

### Verwendete Profile

- `Patient`: `https://fhir.t2med.de/StructureDefinition/FhirApiPatient|1.0.0`
- `Observation Befund`: `https://fhir.t2med.de/StructureDefinition/FhirApiObservationBefund|1.0.0`
- `Observation Anamnese`: `https://fhir.t2med.de/StructureDefinition/FhirApiObservationAnamnese|1.0.0`
- `Observation Freitext`: `https://fhir.t2med.de/StructureDefinition/FhirApiObservationFreitext|1.0.0`
- `Condition Diagnose`: `https://fhir.t2med.de/StructureDefinition/FhirApiConditionDiagnose|1.0.0`
- `Procedure Therapie`: `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureTherapie|1.0.0`
- `Procedure Prozedere`: `https://fhir.t2med.de/StructureDefinition/FhirApiProcedureProcedere|1.0.0`
- `DocumentReference Freitext`: `https://fhir.t2med.de/StructureDefinition/FhirApiDocumentReferenceFreitext|1.0.0`

### Kontext-Identifier

Kontextgebundene Ressourcen verwenden:

- `identifier.system = https://fhir.t2med.de/identifier/kontext`
- `identifier.value = <kontextId>`

## Wichtige Demo-Einschränkungen und Hinweise

- Der API-Key ist in der GUI-Initialisierung fest im Code hinterlegt. Das ist für diese Demo beabsichtigt, weil der verwendete Testschlüssel nicht geheim ist.
- Dieser Schlüssel ist ausschließlich für Demo-, Test- und Integrationsumgebungen gedacht, niemals für Produktion.
- Die App ist auf manuelle Bedienung und Sichtprüfung ausgelegt, nicht auf headless Betrieb.
- Die SSL-Strategie akzeptiert für `https://` bewusst auch lokale, installationsspezifische Zertifikate. Das passt zum lokalen APS-Szenario und ist sicherheitsseitig eine Integrationsentscheidung.

## Build und Start

### Voraussetzungen

- JDK 21 für Build und Laufzeit
- `jpackage` aus einem JDK-21-Setup auf dem Systempfad, wenn ein natives Paket oder App-Image erzeugt werden soll

### JAR bauen

```bash
./gradlew jar
```

Die Fat JAR liegt danach unter:

```text
build/libs/Demoapplikation-1.0-SNAPSHOT.jar
```

### Tests ausführen

```bash
./gradlew test
```

Der Standard-Testlauf enthält nur die CI-tauglichen Unit- und SSL-Tests.

### Integrationstests gezielt ausführen

```bash
export FHIR_BASE_URL=https://127.0.0.1:16567/aps/fhir/api
export FHIR_KONTEXT_ID=<KONTEXT_ID>
export FHIR_OAUTH_TOKEN=<OAUTH_TOKEN>
./gradlew integrationTest
```

Ohne diese Variablen werden Integrationstests übersprungen.

### Native App paketieren

```bash
./gradlew packageApp
```

Der Task verwendet je nach Betriebssystem `jpackage`:

- macOS: App-Image `out/T2demoApp.app`
- Windows: MSI-Paket in `out/`
- Linux: DEB-Paket in `out/`

Optional:

```bash
./gradlew installApp
```

## URL-Scheme-Registrierung

### macOS

Das URL-Scheme `T2demo://` ist in der Vorlage [src/main/resources/macos/Info.plist](./src/main/resources/macos/Info.plist) hinterlegt. `packageApp` merged diese Einträge in das erzeugte App-Bundle. Danach muss die App einmal gestartet oder nach `/Applications` verschoben werden, damit macOS den Handler registriert.

Test:

```bash
open "T2demo://demo/start?kontextId=test&fhirBasisUrl=https%3A%2F%2F127.0.0.1%3A16567%2Faps%2Ffhir%2Fapi&oAuthToken=test-token"
```

### Windows

Das Paket wird über `jpackage` als MSI gebaut. Falls die URL-Scheme-Registrierung nicht durch den Installer abgedeckt ist, muss ein Registry-Eintrag für `T2demo` gesetzt werden, der auf die installierte EXE zeigt.

Beispielaufruf:

```cmd
start T2demo://demo/start?kontextId=test
```

### Linux

Unter Linux erfolgt die Registrierung typischerweise über eine `.desktop`-Datei mit `x-scheme-handler/T2demo` und anschließende Zuordnung über `xdg-settings`.

Beispielaufruf:

```bash
xdg-open "T2demo://demo/start?kontextId=test"
```

## Tests und Verifikation

Die Test-Suite deckt drei Ebenen ab:

- `FhirServiceTest`: Unit-Tests für Such-, Create- und Transaction-Verhalten
- `FhirServiceSslTest`: Verifikation der HTTP-/HTTPS-Konfiguration
- `FhirServiceIntegrationTest`: opt-in Live-Test gegen einen laufenden lokalen APS-/FHIR-Server

Der Integrationstest benötigt einen erreichbaren Server und gültige Testwerte für Basis-URL, Kontext, Token und API-Key. Er ist deshalb vom Standardlauf getrennt und für lokale Verifikation gedacht.

## Empfohlener Schnelltest

  1. JAR oder natives Paket bauen.
  2. App starten oder als URL-Handler registrieren.
  3. Im APS-Client:
     1. Das Benutzerrecht ```Administration|Externe API Drittanbieter-Zugriffe verwalten``` erteilen.
     2. Über die Vorgangssuche den Vorgang ```Drittanbieter-Zugriffe verwalten``` öffnen und gewünschte Anbindung auf "grün" setzen.
     3. In der Button-Leiste Geräteliste öffnen und ```T2demo``` auswählen. Dadurch wird der Deep Link mit `kontextId`, `fhirBasisUrl` und `oAuthToken` aufgerufen.
  4. In der GUI der Demoapp prüfen, ob der `FhirService` initialisiert wurde.
  5. Eine oder mehrere Schreiboperationen testen.
  6. Das `OperationOutcome` bzw. die Bundle-Statuses im Logbereich prüfen.

## Weiterführende Referenz

Für API-Semantik, Fehlercodes, Headerregeln, Profile und Beispielpayloads ist der aktuelle [Integrationsleitfaden-FHIR-API.md](./Integrationsleitfaden-FHIR-API.md) die maßgebliche Fachreferenz.

## Lizenz

Dieses Projekt ist unter der Apache License 2.0 lizenziert. Copyright 2026 T2med GmbH & Co. KG.
