
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
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
        datagrip("2025.2.4")

        // Explicitly add plugins that need ultimate
        bundledPlugin("com.intellij.database")
        //bundledPlugin("com.intellij.java")
    }

    // Jackson YAML for parsing docker-compose files
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }

    sandboxContainer = file("${project.buildDir}/idea-sandbox-ultimate")
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
