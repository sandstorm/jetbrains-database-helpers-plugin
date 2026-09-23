package de.sandstorm.databasehelpers.docker

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.Logger

/**
 * @param environment the real environment, which takes precedence over the `.env` file when
 *   resolving `${...}` placeholders, as it does for compose itself.
 */
class DockerComposeParser(private val environment: Map<String, String> = System.getenv()) {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val logger = Logger.getInstance(DockerComposeParser::class.java)

    /**
     * The database services a compose file declares; empty when it does not parse.
     *
     * @param siblingFile reads a file by its path relative to the compose file, returning null
     *   when there is none. Compose looks there for the `.env` file that supplies placeholder
     *   values and for the `env_file` entries its services point at.
     */
    fun parseDatabases(composeYaml: String, siblingFile: (String) -> String? = { null }): List<DatabaseInfo> {
        val dotEnv = siblingFile(".env")?.let(DotEnv::parse).orEmpty()
        val compose = parse(composeYaml, dotEnv + environment) ?: return emptyList()
        return compose.services
            .mapNotNull { (serviceName, service) ->
                detectDatabaseType(service)?.let { dbType ->
                    extractDatabaseInfo(serviceName, service, dbType, siblingFile)
                }
            }
    }

    private fun parse(composeYaml: String, variables: Map<String, String>): DockerCompose? {
        return try {
            mapper.readValue(interpolate(composeYaml, variables), DockerCompose::class.java)
        } catch (e: Exception) {
            logger.warn("Failed to parse docker-compose file", e)
            null
        }
    }

    /** `env_file` entries apply in order and an inline `environment` entry overrides them all. */
    private fun serviceEnvironment(service: Service, envFileContents: (String) -> String?): Map<String, String> {
        val fromFiles = service.getEnvFilePaths()
            .mapNotNull(envFileContents)
            .fold(emptyMap<String, String>()) { merged, contents -> merged + DotEnv.parse(contents) }
        return fromFiles + service.getEnvironmentMap()
    }

    private fun interpolate(yaml: String, variables: Map<String, String>): String =
        PLACEHOLDER.replace(yaml) { match ->
            if (match.value == "${'$'}${'$'}") return@replace "${'$'}"
            val braceless = match.groupValues[4]
            if (braceless.isNotEmpty()) return@replace variables[braceless] ?: ""
            val (name, separator, fallback) = match.destructured
            val value = variables[name]
            // ":-" falls back for an empty value too, "-" only for a missing one.
            when {
                value == null -> fallback
                value.isEmpty() && separator == ":-" -> fallback
                else -> value
            }
        }

    private companion object {
        /** `${'$'}{NAME}`, `${'$'}{NAME:-default}`, `${'$'}{NAME-default}` or the braceless `${'$'}NAME`;
         * a doubled `${'$'}${'$'}` escapes a literal dollar and is matched first so it wins. */
        private val PLACEHOLDER =
            Regex("""\$\$|\$\{([A-Za-z_][A-Za-z0-9_]*)(?:(:-|-)([^}]*))?}|\$([A-Za-z_][A-Za-z0-9_]*)""")
    }

    private fun detectDatabaseType(service: Service): DatabaseType? {
        val image = service.image?.lowercase() ?: return null
        return when {
            image.contains("postgres") -> DatabaseType.POSTGRES
            image.contains("mysql") -> DatabaseType.MYSQL
            image.contains("mariadb") -> DatabaseType.MARIADB
            else -> null
        }
    }

    private fun extractDatabaseInfo(
        serviceName: String,
        service: Service,
        dbType: DatabaseType,
        envFileContents: (String) -> String?
    ): DatabaseInfo? {
        val env = serviceEnvironment(service, envFileContents)
        val port = extractHostPort(service.ports, getDefaultPort(dbType)) ?: getDefaultPort(dbType)

        val (dbName, username, password) = when (dbType) {
            DatabaseType.POSTGRES -> extractPostgresCredentials(env)
            DatabaseType.MYSQL -> extractMySQLCredentials(env)
            DatabaseType.MARIADB -> extractMariaDBCredentials(env)
        }

        return DatabaseInfo(
            serviceName = serviceName,
            databaseType = dbType,
            port = port,
            databaseName = dbName,
            username = username,
            password = password
        )
    }

    /**
     * The host port the database is reachable on. A service may publish several ports - a
     * metrics exporter alongside the database, say - so the mapping whose container side is
     * the database's own port is preferred, falling back to the first published mapping.
     */
    private fun extractHostPort(ports: List<String>?, containerPort: Int): Int? {
        val mappings = ports?.mapNotNull(::parsePortMapping).orEmpty()
        val match = mappings.firstOrNull { it.container == containerPort } ?: mappings.firstOrNull()
        return match?.host
    }

    /** "HOST:CONTAINER", "[BIND_IP:]HOST:CONTAINER", either side optionally "/PROTOCOL". */
    private fun parsePortMapping(mapping: String): PortMapping? {
        val segments = mapping.substringBefore("/").split(":")
        val container = segments.lastOrNull()?.toIntOrNull() ?: return null
        val host = segments.getOrNull(segments.size - 2)?.toIntOrNull() ?: return null
        return PortMapping(host = host, container = container)
    }

    private data class PortMapping(val host: Int, val container: Int)

    private fun getDefaultPort(dbType: DatabaseType): Int = when (dbType) {
        DatabaseType.POSTGRES -> 5432
        DatabaseType.MYSQL -> 3306
        DatabaseType.MARIADB -> 3306
    }

    private fun extractPostgresCredentials(env: Map<String, String>): Triple<String, String, String> {
        val dbName = env["POSTGRES_DB"] ?: env["POSTGRES_DATABASE"] ?: "postgres"
        val username = env["POSTGRES_USER"] ?: "postgres"
        val password = env["POSTGRES_PASSWORD"] ?: ""
        return Triple(dbName, username, password)
    }

    private fun extractMySQLCredentials(env: Map<String, String>): Triple<String, String, String> {
        val dbName = env["MYSQL_DATABASE"] ?: "mysql"
        val username = env["MYSQL_USER"] ?: "root"
        val password = env["MYSQL_PASSWORD"] ?: env["MYSQL_ROOT_PASSWORD"] ?: ""
        return Triple(dbName, username, password)
    }

    private fun extractMariaDBCredentials(env: Map<String, String>): Triple<String, String, String> {
        val dbName = env["MARIADB_DATABASE"] ?: env["MYSQL_DATABASE"] ?: "mysql"
        val username = env["MARIADB_USER"] ?: env["MYSQL_USER"] ?: "root"
        val password = env["MARIADB_PASSWORD"] ?: env["MARIADB_ROOT_PASSWORD"]
            ?: env["MYSQL_PASSWORD"] ?: env["MYSQL_ROOT_PASSWORD"] ?: ""
        return Triple(dbName, username, password)
    }
}
