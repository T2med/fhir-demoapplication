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

    // Verbose SSL Debug
    // systemProperty("javax.net.debug", "ssl,handshake")
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
    jvmToolchain(11)
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
        val outDir = file("out")
        if (os.isMacOsX) {
            val appBundle = file("out/$appName.app")
            if (appBundle.exists()) {
                println("Lösche altes App-Image: $appBundle")
                appBundle.deleteRecursively()
            }
        } else if (outDir.exists()) {
            println("Lösche alten Output-Ordner: $outDir")
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
                commandLine("msiexec", "/i", msiFile.absolutePath)
            } else {
                throw GradleException("MSI-Datei in out/ nicht gefunden. Wurde packageApp erfolgreich ausgeführt?")
            }
        }
    } else {
        // Linux
        doFirst {
            val debFile = file("out").listFiles()?.find { it.name.endsWith(".deb") }
            if (debFile != null) {
                println("Installiere Debian-Paket ${debFile.absolutePath}...")
                commandLine("sudo", "dpkg", "-i", debFile.absolutePath)
            } else {
                throw GradleException("Debian-Paket in out/ nicht gefunden. Wurde packageApp erfolgreich ausgeführt?")
            }
        }
    }
}
