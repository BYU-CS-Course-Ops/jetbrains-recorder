plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "edu.byu.cs.courseops"
version = "2026-11-23.2"

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
              <li>Require Air and Full Line Code Completion to be disabled before recording starts, skipping absent or already disabled plugins</li>
              <li>Offer a modal Cancel or Disable and Restart choice; keep recording stopped until restart and resume afterward</li>
              <li>Respect saved disabled plugin state to prevent repeated restart prompts on PyCharm 2026.2</li>
              <li>Continue disabling and monitoring available inline-completion settings even when Full Line is already disabled, restoring the previous preference when recording stops</li>
              <li>Silently skip missing inline-completion settings; warn only when an available setting cannot be controlled or verified</li>
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
