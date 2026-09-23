package de.sandstorm.databasehelpers.docker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing and credential-extraction tests. These need no IDE fixture, so they run in
 * milliseconds and are the first thing to look at when a docker-compose file is not
 * picked up as expected.
 */
class DockerComposeParserTest {

    /** @param variables stands in for the real environment, so the host's own cannot leak in. */
    private fun databasesIn(
        yaml: String,
        variables: Map<String, String> = emptyMap(),
        siblingFile: (String) -> String? = { null }
    ): List<DatabaseInfo> =
        DockerComposeParser(environment = variables).parseDatabases(yaml.trimIndent(), siblingFile)

    @Test
    fun `postgres service with environment map is detected`() {
        val databases = databasesIn(
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "15432:5432"
                environment:
                  POSTGRES_DB: myapp
                  POSTGRES_USER: myuser
                  POSTGRES_PASSWORD: mypassword
            """
        )

        assertEquals(1, databases.size)
        val db = databases.single()
        assertEquals("db", db.serviceName)
        assertEquals(DatabaseType.POSTGRES, db.databaseType)
        assertEquals(15432, db.port)
        assertEquals("myapp", db.databaseName)
        assertEquals("myuser", db.username)
        assertEquals("mypassword", db.password)
        assertEquals("postgresql", db.toDriverName())
        assertEquals("jdbc:postgresql://localhost:15432/myapp", db.toJdbcUrl())
    }

    @Test
    fun `environment given as a list is equivalent to a map`() {
        val databases = databasesIn(
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "5432:5432"
                environment:
                  - POSTGRES_DB=myapp
                  - POSTGRES_USER=myuser
                  - POSTGRES_PASSWORD=mypassword
            """
        )

        val db = databases.single()
        assertEquals("myapp", db.databaseName)
        assertEquals("myuser", db.username)
        assertEquals("mypassword", db.password)
    }

    @Test
    fun `environment values containing an equals sign are preserved`() {
        val databases = databasesIn(
            """
            services:
              db:
                image: postgres:16
                environment:
                  - POSTGRES_PASSWORD=p=ss=word
            """
        )

        assertEquals("p=ss=word", databases.single().password)
    }

    @Test
    fun `mysql credentials fall back to the root password`() {
        val databases = databasesIn(
            """
            services:
              mysql:
                image: mysql:8
                ports:
                  - "13306:3306"
                environment:
                  MYSQL_DATABASE: shop
                  MYSQL_ROOT_PASSWORD: rootpw
            """
        )

        val db = databases.single()
        assertEquals(DatabaseType.MYSQL, db.databaseType)
        assertEquals("shop", db.databaseName)
        assertEquals("root", db.username)
        assertEquals("rootpw", db.password)
        assertEquals("jdbc:mysql://localhost:13306/shop", db.toJdbcUrl())
    }

    @Test
    fun `mariadb accepts both MARIADB and MYSQL environment variables`() {
        val databases = databasesIn(
            """
            services:
              maria:
                image: mariadb:11
                ports:
                  - "13307:3306"
                environment:
                  MYSQL_DATABASE: legacy
                  MARIADB_USER: neos
                  MARIADB_PASSWORD: neospw
            """
        )

        val db = databases.single()
        assertEquals(DatabaseType.MARIADB, db.databaseType)
        assertEquals("legacy", db.databaseName)
        assertEquals("neos", db.username)
        assertEquals("neospw", db.password)
        assertEquals("mariadb", db.toDriverName())
    }

    @Test
    fun `default ports are used when no port mapping is published`() {
        assertEquals(
            5432,
            databasesIn(
                """
                services:
                  db:
                    image: postgres:16
                """
            ).single().port
        )

        assertEquals(
            3306,
            databasesIn(
                """
                services:
                  db:
                    image: mysql:8
                """
            ).single().port
        )
    }

    @Test
    fun `non-database services are ignored`() {
        val databases = databasesIn(
            """
            services:
              web:
                image: nginx:latest
                ports:
                  - "8080:80"
              cache:
                image: redis:7
              db:
                image: postgres:16
            """
        )

        assertEquals(1, databases.size)
        assertEquals("db", databases.single().serviceName)
    }

    @Test
    fun `multiple database services are all detected`() {
        val databases = databasesIn(
            """
            services:
              primary:
                image: postgres:16
                ports:
                  - "15432:5432"
              legacy:
                image: mariadb:11
                ports:
                  - "13306:3306"
            """
        )

        assertEquals(2, databases.size)
        assertEquals(
            setOf(DatabaseType.POSTGRES, DatabaseType.MARIADB),
            databases.map { it.databaseType }.toSet()
        )
    }

    @Test
    fun `a compose file with no services yields no databases`() {
        assertTrue(databasesIn("services: {}").isEmpty())
    }

    @Test
    fun `unknown top-level keys do not break parsing`() {
        val databases = databasesIn(
            """
            name: myproject
            volumes:
              pgdata:
            networks:
              default:
                driver: bridge
            services:
              db:
                image: postgres:16
                restart: unless-stopped
                healthcheck:
                  test: ["CMD", "pg_isready"]
            """
        )

        assertEquals(1, databases.size)
    }

    @Test
    fun `malformed yaml yields no databases rather than throwing`() {
        assertTrue(databasesIn("this: is: not: valid: yaml:\n  - [").isEmpty())
    }

    @Test
    fun `credentials come from an env_file, but an explicit environment entry wins`() {
        val db = databasesIn(
            """
            services:
              db:
                image: postgres:16
                env_file: .env.db
                environment:
                  POSTGRES_USER: explicit
            """
        ) { path ->
            if (path == ".env.db") "POSTGRES_USER=fromfile\nPOSTGRES_PASSWORD=filepw" else null
        }.single()

        assertEquals("explicit", db.username)
        assertEquals("filepw", db.password)
    }

    @Test
    fun `a doubled dollar escapes a literal dollar`() {
        val db = databasesIn(
            """
            services:
              db:
                image: postgres:16
                environment:
                  POSTGRES_PASSWORD: "p${'$'}${'$'}${'$'}${'$'}word"
            """,
            variables = mapOf("word" to "NOPE")
        ).single()

        assertEquals("p${'$'}${'$'}word", db.password)
    }

    @Test
    fun `a braceless placeholder is substituted`() {
        val db = databasesIn(
            """
            services:
              db:
                image: postgres:16
                environment:
                  POSTGRES_PASSWORD: ${'$'}DB_PASSWORD
            """,
            variables = mapOf("DB_PASSWORD" to "secret")
        ).single()

        assertEquals("secret", db.password)
    }

    @Test
    fun `a placeholder default is used only when the variable is absent`() {
        val yaml =
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "${'$'}{DB_PORT:-15432}:5432"
                environment:
                  POSTGRES_USER: ${'$'}{DB_USER:-postgres}
            """

        val fallback = databasesIn(yaml).single()
        assertEquals(15432, fallback.port)
        assertEquals("postgres", fallback.username)

        val overridden = databasesIn(yaml, variables = mapOf("DB_PORT" to "25432", "DB_USER" to "neos")).single()
        assertEquals(25432, overridden.port)
        assertEquals("neos", overridden.username)
    }

    @Test
    fun `a placeholder is substituted from the supplied variables`() {
        val db = databasesIn(
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "${'$'}{DB_PORT}:5432"
            """,
            variables = mapOf("DB_PORT" to "15432")
        ).single()

        assertEquals(15432, db.port)
    }

    @Test
    fun `the mapping for the database port wins over other published ports`() {
        val db = databasesIn(
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "9187:9187"
                  - "15432:5432"
            """
        ).single()

        assertEquals(15432, db.port)
    }

    @Test
    fun `a protocol suffix on a port mapping is ignored`() {
        val db = databasesIn(
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "15432:5432/tcp"
            """
        ).single()

        assertEquals(15432, db.port)
    }

    @Test
    fun `a host-qualified port mapping publishes the host port`() {
        val db = databasesIn(
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "127.0.0.1:15432:5432"
            """
        ).single()

        assertEquals(15432, db.port)
        assertEquals("jdbc:postgresql://localhost:15432/postgres", db.toJdbcUrl())
    }
}
