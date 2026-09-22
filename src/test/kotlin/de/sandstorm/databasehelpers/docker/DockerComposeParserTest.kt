package de.sandstorm.databasehelpers.docker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing and credential-extraction tests. These need no IDE fixture, so they run in
 * milliseconds and are the first thing to look at when a docker-compose file is not
 * picked up as expected.
 */
class DockerComposeParserTest {

    private val parser = DockerComposeParser()

    private fun parse(yaml: String): DockerCompose =
        parser.parse(yaml.trimIndent().byteInputStream())
            ?: throw AssertionError("compose file failed to parse")

    private fun databasesIn(yaml: String): List<DatabaseInfo> =
        parser.extractDatabaseConnections(parse(yaml))

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
        val compose = parse(
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

        assertNotNull(compose)
        assertEquals(1, parser.extractDatabaseConnections(compose).size)
    }

    @Test
    fun `malformed yaml returns null rather than throwing`() {
        assertNull(parser.parse("this: is: not: valid: yaml:\n  - [".byteInputStream()))
    }

    /**
     * Documents current behaviour, which is not what you would want: a published port that
     * carries a host interface (the documented "[HOST:]HOST_PORT:CONTAINER_PORT" form) is
     * not understood, so the service silently falls back to the driver default port. See the
     * note in the README about `extractHostPort`.
     */
    @Test
    fun `host-qualified port mappings are currently not parsed`() {
        val db = databasesIn(
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "127.0.0.1:15432:5432"
            """
        ).single()

        assertEquals("falls back to the default port instead of 15432", 5432, db.port)
    }
}
