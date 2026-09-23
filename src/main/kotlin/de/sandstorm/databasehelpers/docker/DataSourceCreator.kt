package de.sandstorm.databasehelpers.docker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.sandstorm.databasehelpers.OpenerCommandLine

class DataSourceCreator {
    private val logger = Logger.getInstance(DataSourceCreator::class.java)
    private val parser = DockerComposeParser()

    fun createOrUpdateDataSources(project: Project, sourceFile: VirtualFile) {
        val directory = sourceFile.parent
        val databases = parser.parseDatabases(textOf(sourceFile)) { path ->
            directory?.findFileByRelativePath(path)?.let(::textOf)
        }

        logger.info("Found ${databases.size} database service(s) in ${sourceFile.path}")
        if (databases.isEmpty()) {
            return
        }
        databases.forEach { db ->
            logger.info("  - ${db.serviceName} (${db.databaseType}) on port ${db.port}")
        }

        val successCount = databases.count { dbInfo ->
            try {
                createDataSource(project, dbInfo, sourceFile)
                true
            } catch (e: Exception) {
                logger.warn("Failed to create data source for ${dbInfo.serviceName}", e)
                false
            }
        }

        if (successCount > 0) {
            showNotification(
                project,
                "Docker Compose Data Sources",
                "Created/updated $successCount data source(s) from $sourceFile",
                NotificationType.INFORMATION
            )
        }
    }

    private fun textOf(file: VirtualFile): String = String(file.contentsToByteArray(), file.charset)

    private fun createDataSource(project: Project, dbInfo: DatabaseInfo, sourceFile: VirtualFile) {
        val dataSourceName = "Docker: ${dbInfo.serviceName}"
        val comment = "Auto-created from ${sourceFile.path} (${dbInfo.databaseType.name.lowercase()} service: ${dbInfo.serviceName})"

        OpenerCommandLine.process(
            currentProject = project,
            driverName = dbInfo.toDriverName(),
            connectionUrl = dbInfo.toJdbcUrl(),
            user = dbInfo.username,
            password = dbInfo.password,
            optionalName = dataSourceName,
            comment = comment
        )
    }

    private fun showNotification(
        project: Project,
        title: String,
        content: String,
        type: NotificationType
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("DataGrip Helpers")
            .createNotification(title, content, type)
            .notify(project)
    }
}
