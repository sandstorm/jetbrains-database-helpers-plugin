package de.sandstorm.databasehelpers.docker

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/**
 * End-to-end coverage of the docker-compose feature against a real, headless IDE: a compose
 * file goes in, and a data source registered with the platform's own [LocalDataSourceManager]
 * comes out.
 *
 * This is the layer that catches platform API breakage across IDE upgrades — the driver
 * lookup, data source construction and registration all run against the real database plugin
 * rather than a stand-in.
 *
 * Note these are JUnit 3 style: the runner discovers methods by their `test` prefix, so an
 * `@Test` annotation alone will not run them.
 */
class DockerComposeDataSourceE2ETest : BasePlatformTestCase() {

    private val creator = DataSourceCreator()

    private val dataSourceManager: LocalDataSourceManager
        get() = LocalDataSourceManager.getInstance(project)

    override fun tearDown() {
        try {
            // The light fixture project is shared between tests in this class, so data
            // sources have to be cleared or they leak into the next test.
            dataSourceManager.dataSources.toList().forEach { dataSourceManager.removeDataSource(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun composeFile(yaml: String, name: String = "docker-compose.yml"): VirtualFile =
        myFixture.addFileToProject(name, yaml.trimIndent()).virtualFile

    /** Runs the production path: parse the file, then create data sources from it. */
    private fun importDataSources(file: VirtualFile) {
        creator.createOrUpdateDataSources(project, file)

        // Data sources are registered from an invokeLater, which is still queued at this point.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun dataSourceNamed(name: String): LocalDataSource =
        dataSourceManager.dataSources.find { it.name == name }
            ?: throw AssertionError(
                "no data source named '$name'; found: " +
                    dataSourceManager.dataSources.joinToString { it.name }
            )

    fun testPostgresComposeFileProducesAConnectableDataSource() {
        importDataSources(
            composeFile(
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
        )

        val ds = dataSourceNamed("Docker: db")
        assertEquals("jdbc:postgresql://localhost:15432/myapp", ds.url)
        assertEquals("myuser", ds.username)
        assertEquals(LocalDataSource.Storage.PERSIST, ds.passwordStorage)
        assertTrue("auto synchronize should be enabled", ds.isAutoSynchronize)
        assertNotNull("driver must resolve against the real database plugin", ds.databaseDriver)
        assertEquals("postgresql", ds.databaseDriver?.id)
    }

    fun testCommentRecordsWhereTheDataSourceCameFrom() {
        val file = composeFile(
            """
            services:
              db:
                image: postgres:16
            """
        )
        importDataSources(file)

        val comment = dataSourceNamed("Docker: db").comment
        assertNotNull("data source should be annotated with its origin", comment)
        assertTrue(
            "comment should reference the source file, was: $comment",
            comment!!.contains(file.path)
        )
    }

    fun testEachDatabaseServiceGetsItsOwnDataSource() {
        importDataSources(
            composeFile(
                """
                services:
                  web:
                    image: nginx:latest
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
        )

        assertEquals(2, dataSourceManager.dataSources.size)
        assertEquals("mariadb", dataSourceNamed("Docker: legacy").databaseDriver?.id)
        assertEquals("postgresql", dataSourceNamed("Docker: primary").databaseDriver?.id)
    }

    /**
     * The documented contract: re-importing a changed compose file updates the existing data
     * source in place. If the UUID changed instead, every console, query history entry and
     * saved setting attached to it would be orphaned.
     */
    fun testReimportUpdatesInPlaceAndPreservesTheDataSourceIdentity() {
        importDataSources(
            composeFile(
                """
                services:
                  db:
                    image: postgres:16
                    ports:
                      - "15432:5432"
                    environment:
                      POSTGRES_USER: olduser
                """
            )
        )

        val originalId = dataSourceNamed("Docker: db").uniqueId
        assertEquals(1, dataSourceManager.dataSources.size)

        importDataSources(
            composeFile(
                """
                services:
                  db:
                    image: postgres:16
                    ports:
                      - "25432:5432"
                    environment:
                      POSTGRES_USER: newuser
                """,
                name = "docker-compose.override.yml"
            )
        )

        assertEquals("a second data source was created instead of updating", 1, dataSourceManager.dataSources.size)
        val updated = dataSourceNamed("Docker: db")
        assertEquals("data source identity must survive an update", originalId, updated.uniqueId)
        assertEquals("newuser", updated.username)
        assertEquals("jdbc:postgresql://localhost:25432/postgres", updated.url)
    }

    fun testComposeFileWithoutDatabasesCreatesNothing() {
        importDataSources(
            composeFile(
                """
                services:
                  web:
                    image: nginx:latest
                  cache:
                    image: redis:7
                """
            )
        )

        assertEmpty(dataSourceManager.dataSources)
    }

    /**
     * The `.env` file next to the compose file supplies the placeholder values, exactly as
     * `docker compose up` would resolve them.
     */
    fun testPlaceholdersAreResolvedFromTheEnvFileNextToTheComposeFile() {
        myFixture.addFileToProject(".env", "DB_PORT=15432\nDB_USER=neos\nDB_PASSWORD=secret\n")
        importDataSources(
            composeFile(
                """
                services:
                  db:
                    image: postgres:16
                    ports:
                      - "127.0.0.1:${'$'}{DB_PORT}:5432"
                    environment:
                      POSTGRES_DB: ${'$'}{DB_NAME:-myapp}
                      POSTGRES_USER: ${'$'}{DB_USER}
                      POSTGRES_PASSWORD: ${'$'}{DB_PASSWORD}
                """
            )
        )

        val ds = dataSourceNamed("Docker: db")
        assertEquals("jdbc:postgresql://localhost:15432/myapp", ds.url)
        assertEquals("neos", ds.username)
    }

    /** A service's `env_file` is resolved relative to the compose file, as compose does. */
    fun testCredentialsAreReadFromAServiceEnvFile() {
        myFixture.addFileToProject("config/db.env", "POSTGRES_USER=fromfile\nPOSTGRES_DB=fromfile_db\n")
        importDataSources(
            composeFile(
                """
                services:
                  db:
                    image: postgres:16
                    env_file: config/db.env
                    ports:
                      - "15432:5432"
                """
            )
        )

        val ds = dataSourceNamed("Docker: db")
        assertEquals("fromfile", ds.username)
        assertEquals("jdbc:postgresql://localhost:15432/fromfile_db", ds.url)
    }

    /**
     * Drives the feature from its real entry point — the startup activity that scans the
     * project — rather than from an already-located file.
     */
    fun testStartupActivityDiscoversComposeFilesInTheProject() {
        composeFile(
            """
            services:
              db:
                image: postgres:16
                ports:
                  - "15432:5432"
                environment:
                  POSTGRES_DB: scanned
            """
        )

        runBlocking { DockerComposeStartupActivity().execute(project) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals("jdbc:postgresql://localhost:15432/scanned", dataSourceNamed("Docker: db").url)
    }
}
