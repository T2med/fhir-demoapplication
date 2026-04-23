import java.util.logging.Logger

/**
 * Hilfsklasse zur Registrierung des T2demo-Protokoll-Handlers unter Windows.
 * Da jpackage/WiX-Anpassungen (overrides.wxi/main.wxs) unter JDK 21 instabil sind,
 * erfolgt die Registrierung hier direkt im Benutzerkontext (HKCU), was keine Admin-Rechte erfordert.
 */
object WindowsProtocolHandler {
    private val logger = Logger.getLogger("WindowsProtocolHandler")

    fun registerIfNecessary() {
        if (!System.getProperty("os.name").contains("win", ignoreCase = true)) {
            return
        }

        try {
            // Den Pfad zur aktuell laufenden .exe ermitteln
            val appPath = ProcessHandle.current().info().command().orElse(null)
            if (appPath == null || !appPath.endsWith(".exe", ignoreCase = true)) {
                // Falls wir nicht als .exe laufen (z.B. im IDE/Gradle-Modus), überspringen
                return
            }

            val protocol = "T2demo"
            val classesPath = "HKCU\\Software\\Classes\\$protocol"

            // 1. Grund-Eintrag für das Protokoll (Standard-Wert setzen)
            runCommand("reg", "add", classesPath, "/ve", "/t", "REG_SZ", "/d", "URL:$protocol Protocol", "/f")

            // 2. Markierung als URL Protocol
            runCommand("reg", "add", classesPath, "/v", "URL Protocol", "/t", "REG_SZ", "/d", "", "/f")

            // 3. Command-Eintrag für den Aufruf der App mit Parameter %1
            val commandPath = "$classesPath\\shell\\open\\command"
            val commandValue = "\\\"$appPath\\\" \\\"%1\\\""
            runCommand("reg", "add", commandPath, "/ve", "/t", "REG_SZ", "/d", commandValue, "/f")

            logger.info("Windows Protocol Handler für $protocol registriert auf: $appPath")
        } catch (e: Exception) {
            logger.warning("Fehler bei der Windows-Protokoll-Registrierung: ${e.message}")
        }
    }

    private fun runCommand(vararg args: String) {
        try {
            val process = ProcessBuilder(*args).start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.warning("Befehl '${args.joinToString(" ")}' fehlgeschlagen mit Exit-Code $exitCode")
            }
        } catch (e: Exception) {
            logger.warning("Fehler beim Ausführen von '${args.getOrNull(0)}': ${e.message}")
        }
    }
}
