package de.sandstorm.databasehelpers.docker

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.sandstorm.databasehelpers.OpenerCommandLine

class DataSourceCreator {
    private val logger = Logger.getInstance(DataSourceCreator::class.java)

    fun createOrUpdateDataSources(project: Project, databases: List<DatabaseInfo>, sourceFile: VirtualFile) {
        if (databases.isEmpty()) {
            return
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
