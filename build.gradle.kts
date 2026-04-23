import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Platform / Kotlin
    implementation(libs.kotlin.stdlib)

    // HAPI FHIR
    implementation(libs.hapi.fhir.base)
    implementation(libs.hapi.fhir.client)
    implementation(libs.hapi.fhir.structures.r4)

    // Utils / Logging
    implementation(libs.guava)
    implementation(libs.httpclient)
    implementation(libs.slf4j.simple)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()

    // Deaktiviere SSL-Validierung für Tests (für selbstsignierte Zertifikate)
    systemProperty("javax.net.ssl.trustAll", "true")
    systemProperty("com.sun.net.ssl.checkRevocation", "false")
    systemProperty("sun.security.ssl.allowUnsafeRenegotiation", "true")
}

application {
    mainClass.set("MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    // Alle Abhängigkeiten in die JAR packen (Fat JAR)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

kotlin {
    jvmToolchain(21)
}

val appName = "T2demoApp"
val mainJar = "Demoapplikation-1.0-SNAPSHOT.jar"
val mainClass = "MainKt"

tasks.register<Exec>("packageApp") {
    dependsOn("jar")
    group = "distribution"
    description = "Erstellt ein App-Image oder Paket mit jpackage."

    workingDir = projectDir

    val os = OperatingSystem.current()

    doFirst {
        if (OperatingSystem.current().isWindows) {
            // WiX-Pfad dynamisch finden und zur JVM-Umgebung hinzufügen
            val wixLocations = listOf(
                "C:\\Program Files (x86)\\WiX Toolset v3.11\\bin",
                "C:\\Program Files\\WiX Toolset v3.11\\bin",
                "C:\\Program Files (x86)\\WiX Toolset v3.14\\bin",
                "C:\\Program Files\\WiX Toolset v3.14\\bin"
            )


            val wixPath = wixLocations.firstOrNull {
                File("$it\\candle.exe").exists()
            } ?: run {
                // Fallback: Suche im Dateisystem
                sequenceOf(
                    File("C:\\Program Files (x86)"),
                    File("C:\\Program Files")
                ).flatMap { it.walk() }
                    .firstOrNull { it.name == "candle.exe" }
                    ?.parent
                    ?: throw GradleException("WiX Toolset nicht gefunden!")
            }

            println("Verwende WiX aus: $wixPath")

            // PATH für diesen Prozess und child-Prozesse setzen
            val currentPath = System.getenv("PATH") ?: ""
            environment("PATH", "$wixPath;$currentPath")
        }

        val outDir = file("out")
        if (os.isMacOsX) {
            file("out/$appName.app").takeIf { it.exists() }?.deleteRecursively()
        } else if (outDir.exists()) {
            outDir.deleteRecursively()
        }
        outDir.mkdirs()
    }


    if (os.isMacOsX) {
        commandLine(
            "jpackage",
            "--input", "build/libs/",
            "--dest", "out/",
            "--name", appName,
            "--main-jar", mainJar,
            "--main-class", mainClass,
            "--type", "app-image"
        )
    } else if (os.isWindows) {
        commandLine(
            "jpackage",
            "--verbose",
            "--vendor", "T2med",
            "--app-version", "1.0.0",
            "--input", "build/libs/",
            "--dest", "out/",
            "--name", appName,
            "--main-jar", mainJar,
            "--main-class", mainClass,
            "--type", "msi",
            "--win-shortcut",
            "--win-menu"
        )
    } else {
        // Linux
        commandLine(
            "jpackage",
            "--input", "build/libs/",
            "--dest", "out/",
            "--name", appName.lowercase(),
            "--main-jar", mainJar,
            "--main-class", mainClass,
            "--type", "deb"
        )
    }

    doLast {
        if (os.isMacOsX) {
            // Die Info.plist unter src/main/resources/macos dient als Vorlage fuer benutzerdefinierte Keys,
            // die jpackage standardmaessig nicht setzt oder beim Generieren ueberschreibt.
            val sourcePlist = file("src/main/resources/macos/Info.plist")
            val targetPlist = file("out/$appName.app/Contents/Info.plist")

            if (sourcePlist.exists() && targetPlist.exists()) {
                println("Aktualisiere Info.plist mit Differenzen aus $sourcePlist...")
                // Wir verwenden PlistBuddy (macOS Standard), um die Keys zu mergen.
                // Einfache Strategie: Wir lesen alle Keys aus der Quell-Plist und setzen sie in der Ziel-Plist.
                // Das entspricht dem Wunsch, die Differenzen zu ergänzen/aktualisieren.

                val keysToMerge = listOf(
                    "NSMicrophoneUsageDescription",
                    "CFBundleURLTypes",
                    "LSApplicationCategoryType",
                    "NSHighResolutionCapable"
                )

                keysToMerge.forEach { key ->
                    // Versuche den Wert aus der Quelle zu lesen
                    val readValueProcess = ProcessBuilder("/usr/libexec/PlistBuddy", "-x", "-c", "Print :$key", sourcePlist.absolutePath).start()
                    val xmlValue = readValueProcess.inputStream.bufferedReader().readText()
                    readValueProcess.waitFor()

                    if (readValueProcess.exitValue() == 0 && xmlValue.isNotBlank()) {
                        // Wert existiert in Quelle.
                        // Wir bauen ein temporäres Plist-File, das nur diesen einen Key enthält,
                        // damit wir ihn sauber ins Ziel mergen können.
                        val tempFile = File.createTempFile("plist_entry", ".plist")

                        // Wir müssen das XML-Snippet einbetten in ein Dict mit dem Key,
                        // da PlistBuddy beim Merge auf Wurzel-Ebene ein Dict erwartet.
                        // Das XML von PlistBuddy -x Print enthält bereits den Header.
                        // Wir müssen den Header evtl. entfernen oder ein neues XML bauen.

                        // Wir bauen ein minimales Plist-XML mit dem Key und dem Wert (der bereits XML ist).
                        // Da der Wert von PlistBuddy schon Header und DOCTYPE enthält, extrahieren wir nur den Content zwischen <plist>...</plist>
                        val contentMatcher = Regex("<plist[^>]*>(.*)</plist>", RegexOption.DOT_MATCHES_ALL).find(xmlValue)
                        val innerXml = contentMatcher?.groupValues?.get(1)?.trim() ?: xmlValue

                        val fullXml = """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                            <plist version="1.0">
                            <dict>
                                <key>$key</key>
                                $innerXml
                            </dict>
                            </plist>
                        """.trimIndent()

                        tempFile.writeText(fullXml)

                        exec {
                            commandLine("/usr/libexec/PlistBuddy", "-c", "Delete :$key", targetPlist.absolutePath)
                            isIgnoreExitValue = true // Falls Key nicht existiert
                        }

                        exec {
                            commandLine("/usr/libexec/PlistBuddy", "-c", "Merge ${tempFile.absolutePath} :", targetPlist.absolutePath)
                        }

                        tempFile.delete()
                    }
                }
            }
        } else if (os.isWindows) {
            // Die Registry-Einträge werden nun direkt von der App beim Start erstellt (WindowsProtocolHandler),
            // da WiX-Anpassungen unter JDK 21 instabil sind.
            // Wir erstellen die .reg Datei trotzdem noch als Backup/Referenz für den Benutzer.
            val regFile = file("out/T2demo.reg")
            println("Erstelle Registry-Datei als Referenz: ${regFile.absolutePath}")
            val regContent = """
                Windows Registry Editor Version 5.00

                [HKEY_CURRENT_USER\Software\Classes\T2demo]
                @="URL:T2demo Protocol"
                "URL Protocol"=""

                [HKEY_CURRENT_USER\Software\Classes\T2demo\shell]

                [HKEY_CURRENT_USER\Software\Classes\T2demo\shell\open]

                [HKEY_CURRENT_USER\Software\Classes\T2demo\shell\open\command]
                @="\"C:\\Program Files\\T2demoApp\\T2demoApp.exe\" \"%1\""
            """.trimIndent().replace("\n", "\r\n") // Windows nutzt CRLF
            regFile.writeText(regContent)
        } else if (os.isLinux) {
            val desktopFile = file("out/t2demo.desktop")
            println("Erstelle Desktop-Datei: ${desktopFile.absolutePath}")
            val desktopContent = """
                [Desktop Entry]
                Name=T2demo App
                Exec=/usr/bin/${appName.lowercase()} %u
                Type=Application
                Terminal=false
                MimeType=x-scheme-handler/T2demo;
            """.trimIndent()
            desktopFile.writeText(desktopContent)
        }
    }
}

tasks.register<Exec>("installApp") {
    dependsOn("packageApp")
    group = "distribution"
    description = "Installiert die App auf dem System."

    val os = OperatingSystem.current()

    if (os.isMacOsX) {
        commandLine("bash", "-c", "rm -rf /Applications/$appName.app && mv out/$appName.app /Applications/")
    } else if (os.isWindows) {
        doFirst {
            val msiFile = file("out").listFiles()?.find { it.name.endsWith(".msi") }
            if (msiFile != null) {
                println("Starte Installation von ${msiFile.absolutePath}...")
                // Installation ausführen
                exec {
                    commandLine("msiexec", "/i", msiFile.absolutePath, "/qn")
                }
                println("Installation abgeschlossen. Registry-Einträge werden beim ersten App-Start (WindowsProtocolHandler) gesetzt.")
            } else {
                throw GradleException("MSI-Datei in out/ nicht gefunden. Wurde packageApp erfolgreich ausgeführt?")
            }
        }
    } else {
        // Linux
        doFirst {
            val debFile = file("out").listFiles()?.find { it.name.endsWith(".deb") }
            val desktopFile = file("out/t2demo.desktop")
            if (debFile != null) {
                println("Installiere Debian-Paket ${debFile.absolutePath}...")
                exec {
                    commandLine("sudo", "dpkg", "-i", debFile.absolutePath)
                }
                // Desktop-Datei registrieren
                if (desktopFile.exists()) {
                    println("Registriere URL-Handler unter Linux...")
                    val userDesktopDir = file("${System.getProperty("user.home")}/.local/share/applications")
                    userDesktopDir.mkdirs()
                    val targetDesktopFile = file("${userDesktopDir.absolutePath}/t2demo.desktop")
                    desktopFile.copyTo(targetDesktopFile, overwrite = true)

                    exec {
                        commandLine("update-desktop-database", userDesktopDir.absolutePath)
                    }
                    exec {
                        commandLine("xdg-settings", "set", "default-url-scheme-handler", "T2demo", "t2demo.desktop")
                    }
                }
            } else {
                throw GradleException("Debian-Paket in out/ nicht gefunden. Wurde packageApp erfolgreich ausgeführt?")
            }
        }
    }
}
