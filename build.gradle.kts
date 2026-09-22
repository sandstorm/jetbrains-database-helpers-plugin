
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

// ValueSource for configuration-cache-compatible git version computation
abstract class GitVersionValueSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String {
        fun executeGit(vararg args: String): String? {
            return try {
                val process = ProcessBuilder("git", *args)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()
                process.waitFor()
                if (process.exitValue() == 0) {
                    process.inputStream.bufferedReader().readText().trim()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        // Check if we're currently on a tag
        executeGit("describe", "--tags", "--exact-match")?.let { tag ->
            return tag.removePrefix("v")
        }

        // Get the last tag and increment
        executeGit("describe", "--tags", "--abbrev=0")?.let { lastTag ->
            val version = lastTag.removePrefix("v")
            val parts = version.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return "$major.$minor.${patch + 1}-SNAPSHOT"
        }

        // No tags found, use default
        return "0.1.0-SNAPSHOT"
    }
}

group = "de.sandstorm.databasehelpers"
version = providers.of(GitVersionValueSource::class.java) {}.get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        datagrip("2026.2.5")

        // Explicitly add plugins that need ultimate
        bundledPlugin("com.intellij.database")
        //bundledPlugin("com.intellij.java")

        // Headless IDE fixture used by the end-to-end tests
        testFramework(TestFrameworkType.Platform)
    }

    // The platform test framework is JUnit 3/4 based, so JUnit 4 is required.
    testImplementation("junit:junit:4.13.2")

    // Jackson YAML for parsing docker-compose files
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262.10315"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }

    sandboxContainer = layout.buildDirectory.dir("idea-sandbox-ultimate")

    pluginVerification {
        ides {
            create(IntelliJPlatformType.DataGrip, "2026.2.5")
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "25"
        targetCompatibility = "25"
    }

    test {
        // BasePlatformTestCase is JUnit 3/4 based - do NOT switch to useJUnitPlatform().
        // The headless IDE fixture needs more heap than the Gradle default.
        maxHeapSize = "2g"
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
