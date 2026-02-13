package de.sandstorm.datagriphelpers;

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.*
import com.intellij.database.dataSource.validation.DatabaseDriverValidator
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbDataSource
import com.intellij.database.util.DataSourceUtil
import com.intellij.database.util.DbImplUtil
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternNode
import com.intellij.database.util.TreePatternUtils
import com.intellij.ide.CliResult
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ApplicationStarterBase
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.OpenSourceUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future


class OpenerCommandLine : ApplicationStarter {

    //override fun getCommandName(): String = "opensql"

    override fun canProcessExternalCommandLine(): Boolean = true

    /*override fun getUsageMessage(): String =
        """
            Run as: idea|datagrip opensql [driverName] [connectionUrl] [user] [password] [optional name]
            
            datagrip opensql postgresql jdbc:postgresql://localhost:5432/postgres admin password title-here 
            datagrip opensql mariadb jdbc:mariadb://localhost:3306 root password title-here
            datagrip opensql sqlite.xerial jdbc:sqlite:/your/path/to/db.sqlite - - title-here
            
        """.trimIndent()*/

    override fun main(args: List<String>){
        val driverName = args[1] // like postgresql
        val connectionUrl = args[2]
        val user = args[3]
        val password = args[4]
        val optionalName = args[5]

        process(driverName, connectionUrl, user, password, optionalName)
    }


    override suspend fun processExternalCommandLine(args: List<String>, currentDirectory: String?): CliResult {
        val driverName = args[1] // like postgresql
        val connectionUrl = args[2]
        val user = args[3]
        val password = args[4]
        val optionalName = args[5]

        process(driverName, connectionUrl, user, password, optionalName)
        return CliResult.OK
    }
    companion object {
        fun process(
            driverName: String,
            connectionUrl: String,
            user: String,
            password: String,
            optionalName: String
        ): Future<CliResult> {
            val instance: DatabaseDriverManager = DatabaseDriverManagerImpl.getInstance()
            val containsDriver = instance.drivers.find { driver -> driver.id == driverName } !== null

            if (!containsDriver) {
                val supportedDrivers = instance.drivers.joinToString(", ") { driver -> driver.id }
                throw RuntimeException("The driver $driverName is not supported. Supported drivers are: $supportedDrivers")
            }

            val driver: DatabaseDriver = instance.getDriver(driverName)

            val currentProject: Project? = PlatformDataKeys.PROJECT.getData(
                DataManager.getInstance().dataContextFromFocus.result
            )

            if (currentProject == null) {
                throw RuntimeException("Current project could not be found");
            }

            val targetName = if (optionalName.isNotEmpty()) optionalName else "(generated) $connectionUrl"

            // Get project-specific data source storage
            val storage = LocalDataSourceManager.getInstance(currentProject)

            // Remove any existing data sources with the same name or URL
            storage.dataSources.filter { el ->
                el.name == targetName || el.url == connectionUrl
            }.forEach { existingDs ->
                storage.removeDataSource(existingDs)
            }

            // Create new data source with current settings
            val ds = LocalDataSource()
            ds.name = targetName
            ds.databaseDriver = driver
            ds.url = connectionUrl
            ds.username = user
            ds.passwordStorage = LocalDataSource.Storage.PERSIST
            storage.addDataSource(ds)

            // Store password
            DatabaseCredentials.getInstance().storePassword(ds, OneTimeString(password))
            ds.resolveDriver()
            ds.ensureDriverConfigured()

            // Enable auto-sync for the data source
            ds.isAutoSynchronize = true

            // Configure schema selection to select all schemas
            try {
                val schemaMapping: DataSourceSchemaMapping = ds.schemaMapping ?: DataSourceSchemaMapping()

                // Create a TreePattern that matches all schemas (wildcard pattern)
                val nullObjectName = ObjectName.NULL
                val schemaNode = TreePatternUtils.create(nullObjectName, ObjectKind.SCHEMA)
                val allSchemasPattern = TreePattern(schemaNode)

                schemaMapping.setIntrospectionScope(allSchemasPattern)
                ds.schemaMapping = schemaMapping
            } catch (e: Exception) {
                // Silently fail - schema selection will need to be done manually
            }

            // Run blocking operations in a background task
            val future = CompletableFuture<CliResult>()

            ProgressManager.getInstance().run(object : Task.Backgroundable(currentProject, "Connecting to Database", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Downloading database drivers..."
                        downloadDrivers(ds)

                        indicator.text = "Connecting and syncing schema..."
                        // Get the DbDataSource for further operations
                        val dbDataSource: DbDataSource? = DbImplUtil.getDbDataSource(project, ds)

                        if (dbDataSource != null) {
                            // Sync the schema (this will also trigger connection)
                            DataSourceUtil.performAutoSyncTask(project, ds)

                            // Open/navigate to the data source (opens console) - must be done on EDT
                            invokeLater {
                                OpenSourceUtil.navigate(true, true, dbDataSource)
                            }
                        }

                        future.complete(CliResult.OK)
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }
            })

            return future
        }

        // see https://github.com/kassak/dg-exposer/blob/d944fdcbb77b47bc37501f4b223427f7c0435112/dg-exposer/main/src/com/github/kassak/intellij/expose/ProjectHandler.java#L131
        private fun downloadDrivers(ds: LocalDataSource) {
            if (DbImplUtil.hasDriverFiles(ds)) return
            val driver = ds.databaseDriver
            if (driver != null && driver.artifacts.size > 0) {
                val task = DatabaseDriverValidator.createDownloaderTask(ds, null)
                task.run(EmptyProgressIndicator())
            }

        }
    }
}
