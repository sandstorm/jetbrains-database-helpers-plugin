package de.sandstorm.databasehelpers.docker

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerCompose(
    val version: String? = null,
    val services: Map<String, Service> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Service(
    val image: String? = null,
    val ports: List<String>? = null,
    val environment: Any? = null, // Can be List<String> or Map<String, String>
    @JsonProperty("env_file") val envFile: Any? = null, // Can be a single path or a list of them
    @JsonProperty("container_name") val containerName: String? = null
) {
    /** The `env_file` paths, relative to the compose file, in the order compose applies them. */
    fun getEnvFilePaths(): List<String> = when (envFile) {
        is String -> listOf(envFile)
        is List<*> -> envFile.mapNotNull { entry ->
            when (entry) {
                is String -> entry
                // The long form is `- path: .env.db` with optional `required:`.
                is Map<*, *> -> entry["path"]?.toString()
                else -> null
            }
        }
        else -> emptyList()
    }

    fun getEnvironmentMap(): Map<String, String> {
        return when (environment) {
            is Map<*, *> -> environment.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
            is List<*> -> environment.mapNotNull { it?.toString() }
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap()
            else -> emptyMap()
        }
    }
}

data class DatabaseInfo(
    val serviceName: String,
    val databaseType: DatabaseType,
    val host: String = "localhost",
    val port: Int,
    val databaseName: String,
    val username: String,
    val password: String
) {
    fun toDriverName(): String = when (databaseType) {
        DatabaseType.POSTGRES -> "postgresql"
        DatabaseType.MYSQL -> "mysql"
        DatabaseType.MARIADB -> "mariadb"
    }

    fun toJdbcUrl(): String = when (databaseType) {
        DatabaseType.POSTGRES -> "jdbc:postgresql://$host:$port/$databaseName"
        DatabaseType.MYSQL -> "jdbc:mysql://$host:$port/$databaseName"
        DatabaseType.MARIADB -> "jdbc:mariadb://$host:$port/$databaseName"
    }
}

enum class DatabaseType {
    POSTGRES,
    MYSQL,
    MARIADB
}
