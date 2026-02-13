package de.sandstorm.datagriphelpers;

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.*
import com.intellij.database.dataSource.validation.DatabaseDriverValidator
import com.intellij.database.util.DbImplUtil
import com.intellij.ide.CliResult
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ApplicationStarterBase
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
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

            val ds = LocalDataSource() // note: the one using the password is deprecated

            if (optionalName.isNotEmpty()) {
                ds.name = optionalName
            } else {
                ds.name = "(generated) $connectionUrl"
            }

            ds.databaseDriver = driver
            ds.url = connectionUrl
            ds.username = user
            ds.passwordStorage = LocalDataSource.Storage.PERSIST


            val currentProject: Project? = PlatformDataKeys.PROJECT.getData(
                DataManager.getInstance().dataContextFromFocus.result
            )

            if (currentProject == null) {
                throw RuntimeException("Current project could not be found");
            }

            DatabaseCredentials.getInstance().storePassword(ds, OneTimeString(password))
            ds.resolveDriver()
            ds.ensureDriverConfigured()
            DataSourceStorage.getStorage().dataSources.filter { el ->
                el.name == ds.name
            }.forEach { existingDs ->
                DataSourceStorage.getStorage().removeDataSource(existingDs)
            }
            DataSourceStorage.getStorage().addDataSource(ds)


            /*val dbDataSource: DbDataSource? = DbImplUtil.getDbDataSource(currentProject, ds)

            if (dbDataSource == null) {
                throw RuntimeException("dbDataSource could not be found");
            }*/

            // Triggering download of source:
            downloadDrivers(ds)

            DataSourceStorage.getStorage()

            //OpenSourceUtil.navigate(true, true, dbDataSource)

            return CompletableFuture.completedFuture(CliResult.OK)
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
