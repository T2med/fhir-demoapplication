# T2demo Custom URL App

Diese Demo-Applikation demonstriert die Verarbeitung von Custom URL Schemes unter macOS und Windows/Linux.

## Funktionsweise
Die Applikation zeigt die aufgerufene URL und die darin enthaltenen Query-Parameter an. Sie kann über das Protokoll `T2demo://` aufgerufen werden.

## Gradle-Projekt und App-Bundle Erstellung (macOS)

Damit das Betriebssystem auf den Aufruf `open T2demo://...` reagiert, muss die App als macOS-Bundle (`.app`) registriert sein. Da wir Gradle nutzen, ist der Prozess nun wie folgt:

### 1. Die Applikation als JAR bauen (Fat JAR)
Du kannst die JAR-Datei direkt aus IntelliJ IDEA heraus erstellen:
1. Öffne das **Gradle-Tool Window** (rechts an der Seite).
2. Navigiere zu `Tasks` -> `build` -> `jar`.
3. Führe den Task per Doppelklick aus.
*Die JAR-Datei wird unter `build/libs/Demoapplikation-1.0-SNAPSHOT.jar` erstellt.*

**Sollte `./gradlew` im Terminal fehlen:**
Da du Gradle installiert hast, aber der Wrapper (`gradlew`) fehlt, kannst du ihn so in IDEA generieren:
- Rechtsklick auf das Projekt -> `Gradle` -> `Reload Gradle Project`.
- Oder im Terminal: `gradle wrapper` (sofern `gradle` in deinem PATH liegt).
- Sobald die Dateien `gradlew` und der Ordner `gradle/` existieren, kannst du `./gradlew jar` nutzen.

Alternativ über das Terminal (wenn der Wrapper vorhanden ist):
```bash
./gradlew jar
```

### 2. Das App-Bundle mit `jpackage` erstellen
`jpackage` (ab JDK 14) nutzt nun die von Gradle erstellte JAR-Datei:
```bash
jpackage --input build/libs/ \
         --dest out/ \
         --name "T2demoApp" \
         --main-jar Demoapplikation-1.0-SNAPSHOT.jar \
         --main-class MainKt \
         --type app-image
```
*Die App liegt nun unter `out/T2demoApp.app`.*

### 3. Das URL-Scheme in der Info.plist registrieren
Damit macOS weiß, dass diese App für `T2demo://` zuständig ist, muss die Datei `out/T2demoApp.app/Contents/Info.plist` angepasst werden.

**Öffne die Datei (z.B. mit TextEdit) und füge vor dem letzten `</dict>` folgendes ein:**
```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleURLName</key>
        <string>T2demo Handler</string>
        <key>CFBundleURLSchemes</key>
        <array>
            <string>T2demo</string>
        </array>
    </dict>
</array>
```

### 4. Die App registrieren
Verschiebe die `T2demoApp.app` einmal in deinen Programme-Ordner (`/Applications`) oder starte sie einmal manuell per Doppelklick. Dadurch registriert macOS das neue URL-Scheme.

### 5. Den Aufruf testen
Jetzt kannst du im Terminal testen, ob es funktioniert:
```bash
open "T2demo://hallo/welt?status=erfolgreich"
```
*Die App sollte sich öffnen (falls noch nicht offen) und die URL-Daten in der GUI anzeigen.*

---

## Windows Inbetriebnahme (für T2demo://)

Unter Windows erfolgt die Registrierung eines Custom URL Schemes über die Windows Registry.

### 1. Das Programm mit `jpackage` paketieren
Erstelle einen MSI-Installer oder ein EXE-Paket:
```powershell
jpackage --input build/libs/ `
         --dest out/ `
         --name "T2demoApp" `
         --main-jar Demoapplikation-1.0-SNAPSHOT.jar `
         --main-class MainKt `
         --type msi `
         --win-shortcut `
         --win-menu
```
*Installiere die App anschließend über die erzeugte `.msi` Datei.*

### 2. Das URL-Scheme in der Registry registrieren
Damit Windows weiß, dass `T2demo://` von deiner App verarbeitet werden soll, erstelle eine Datei namens `T2demo.reg` mit folgendem Inhalt (Pfade ggf. anpassen):

```reg
Windows Registry Editor Version 5.00

[HKEY_CLASSES_ROOT\T2demo]
@="URL:T2demo Protocol"
"URL Protocol"=""

[HKEY_CLASSES_ROOT\T2demo\shell]

[HKEY_CLASSES_ROOT\T2demo\shell\open]

[HKEY_CLASSES_ROOT\T2demo\shell\open\command]
@="\"C:\\Program Files\\T2demoApp\\T2demoApp.exe\" \"%1\""
```
*Führe die `.reg` Datei per Doppelklick aus, um die Einträge hinzuzufügen.*

### 3. Den Aufruf testen
Du kannst den Aufruf über die Eingabeaufforderung (CMD) oder den "Ausführen"-Dialog (Win+R) testen:
```cmd
start T2demo://test/windows?user=Admin
```

---

## Linux Inbetriebnahme (für T2demo://)

Unter Linux (Gnome/KDE/XFCE) erfolgt die Registrierung über eine `.desktop` Datei und `xdg-settings`.

### 1. Das Programm mit `jpackage` paketieren
Erstelle ein DEB oder RPM Paket (je nach Distribution):
```bash
jpackage --input build/libs/ \
         --dest out/ \
         --name "t2demo-app" \
         --main-jar Demoapplikation-1.0-SNAPSHOT.jar \
         --main-class MainKt \
         --type deb
```
*Installiere das Paket (z.B. mit `sudo dpkg -i out/t2demo-app_1.0_amd64.deb`).*

### 2. Den Desktop-Eintrag konfigurieren
Falls der Installer die Registrierung nicht automatisch übernimmt, erstelle die Datei `~/.local/share/applications/t2demo.desktop`:

```ini
[Desktop Entry]
Name=T2demo App
Exec=/usr/bin/t2demo-app %u
Type=Application
Terminal=false
MimeType=x-scheme-handler/T2demo;
```

### 3. Das URL-Scheme registrieren
Führe folgende Befehle im Terminal aus:
```bash
# MIME-Type Datenbank aktualisieren
update-desktop-database ~/.local/share/applications/

# Als Standard-Handler für T2demo festlegen
xdg-settings set default-url-scheme-handler T2demo t2demo.desktop
```

### 4. Den Aufruf testen
```bash
xdg-open "T2demo://linux/test?os=ubuntu"
```
