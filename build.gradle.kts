plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "edu.byu.cs.courseops"
version = "2026-11-04.1"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(kotlin("test-junit"))

    intellijPlatform {
        create("IC", "2025.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "BYU CS Code Recorder"

        description = """
            Automatically records code changes in your projects. The plugin captures every edit,
            deletion, and modification to files without recording your screen. Recording starts
            automatically on IDE startup and provides visual feedback with a bright green
            indicator when edits are recorded.
        """.trimIndent()

        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            <ul>
              <li>Temporarily disable JetBrains language-model inline completion while recording and restore the previous preference when recording stops</li>
              <li>Detect and revert attempts to re-enable inline completion during recording</li>
              <li>Distinguish automatic setting changes from observed external changes with recorderSettingChanged and userSettingChanged recording events</li>
              <li>Show one warning per recording session with a Settings button when inline completion cannot be controlled or verified</li>
              <li>Preserve the original preference across restarts and improve compatibility with modular PyCharm settings services</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("JETBRAINS_CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("JETBRAINS_PRIVATE_KEY")
        password = providers.environmentVariable("JETBRAINS_PRIVATE_KEY_PASSWORD")
    }
}
