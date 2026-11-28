plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

sourceSets["main"].kotlin.srcDir("../shared/src")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.java.dev.jna:jna:5.19.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.12.1")
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.jurisgames.papeles.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "Papeles"
            packageVersion = "1.0.1"
            vendor = "Juris Games"
            description = "PDF tools on your own device, no accounts, free"
            modules("jdk.unsupported")
            // U-P21: Windows offers Papeles in "Open with" for PDFs (the file arrives on the command line)
            fileAssociation(mimeType = "application/pdf", extension = "pdf", description = "PDF document")
            // scripts/package-desktop.sh puts pp_ffi and pdfium here, per OS.
            appResourcesRootDir.set(layout.buildDirectory.dir("native"))
            windows {
                menuGroup = "Papeles"
                perUserInstall = true
                shortcut = true
                upgradeUuid = "5b0f0c3e-6a51-4c1e-9d0e-3f7a2c8e91b4"
            }
        }
    }
}

// Tests use the Rust library built by `cargo build -p pp-ffi` and PDFium from scripts/fetch_pdfium.sh.
val rustTarget = rootDir.resolve("../target/debug")
tasks.test {
    systemProperty("jna.library.path", rustTarget.absolutePath)
    systemProperty("pp.pdfium", rootDir.resolve("../vendor/pdfium/win-x64/bin").absolutePath)
    // The app follows the device language; tests run in English unless asked: -Plang=es
    systemProperty("pp.lang", (project.findProperty("lang") as String?) ?: "en")
    // ScreenshotTest writes PNGs only when asked: gradlew :desktop:test -Pshots=<folder>
    systemProperty("pp.shots", (project.findProperty("shots") as String?) ?: "")
    // ...and at the Play store size: -Pstore=true
    systemProperty("pp.shots.store", (project.findProperty("store") as String?) ?: "")
}
