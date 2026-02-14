package de.sandstorm.datagriphelpers.docker

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream

class DockerComposeParser {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val logger = Logger.getInstance(DockerComposeParser::class.java)

    fun parse(inputStream: InputStream): DockerCompose? {
        return try {
            mapper.readValue(inputStream, DockerCompose::class.java)
        } catch (e: Exception) {
            logger.warn("Failed to parse docker-compose file", e)
            null
        }
    }

    fun extractDatabaseConnections(compose: DockerCompose): List<DatabaseInfo> {
        return compose.services
            .mapNotNull { (serviceName, service) ->
                detectDatabaseType(service)?.let { dbType ->
                    extractDatabaseInfo(serviceName, service, dbType)
                }
            }
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
        dbType: DatabaseType
    ): DatabaseInfo? {
        val env = service.getEnvironmentMap()
        val port = extractHostPort(service.ports) ?: getDefaultPort(dbType)

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

    private fun extractHostPort(ports: List<String>?): Int? {
        // Parse formats like "13306:3306" or "5432:5432"
        return ports?.firstOrNull()
            ?.split(":")
            ?.firstOrNull()
            ?.toIntOrNull()
    }

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
