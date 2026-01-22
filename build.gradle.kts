plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "edu.byu.cs.courseops"
version = "2026-01-22.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2025.1.4.1")
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
              <li>Always auto-start recording on IDE startup and after plugin updates</li>
              <li>Added visual feedback: indicator flashes bright green when edits are recorded</li>
              <li>Improved workspace root initialization for reliable recording after restarts</li>
            </ul>
        """.trimIndent()
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
