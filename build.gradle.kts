plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "edu.byu.cs.courseops"
version = "2026-04-02.2"

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

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
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
              <li>Added recorder version metadata and snapshot fallback for inconsistent edits</li>
              <li>Always auto-start recording on IDE startup and after plugin updates</li>
              <li>Added visual feedback: indicator flashes bright green when edits are recorded</li>
              <li>Improved workspace root initialization for reliable recording after restarts</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            create("IC", "2025.1")
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

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
