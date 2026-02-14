package de.sandstorm.datagriphelpers.docker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class DockerComposeFileListener : BulkFileListener {
    private val logger = Logger.getInstance(DockerComposeFileListener::class.java)
    private val parser = DockerComposeParser()
    private val creator = DataSourceCreator()

    override fun after(events: List<VFileEvent>) {
        events.forEach { event ->
            val file = event.file ?: return@forEach

            if (isDockerComposeFile(file)) {
                when (event) {
                    is VFileContentChangeEvent, is VFileCreateEvent -> {
                        logger.info("Docker-compose file changed: ${file.path}")
                        handleDockerComposeChange(file)
                    }
                }
            }
        }
    }

    private fun isDockerComposeFile(file: VirtualFile): Boolean {
        return file.name.matches(Regex("docker-compose.*\\.(yml|yaml)"))
    }

    private fun handleDockerComposeChange(file: VirtualFile) {
        // Find the project that contains this file
        val project = ProjectManager.getInstance().openProjects.firstOrNull { project ->
            project.basePath?.let { file.path.startsWith(it) } ?: false
        } ?: run {
            logger.info("No project found for file: ${file.path}")
            return
        }

        // Check depth limit (max 2 levels deep from project root)
        val baseDir = project.baseDir
        if (baseDir != null) {
            val depth = calculateDepth(baseDir.path, file.path)
            if (depth > 2) {
                logger.warn("Ignoring docker-compose file at depth $depth (max depth: 2): ${file.path}")
                return
            }
            logger.warn("Processing docker-compose file at depth $depth: ${file.path}")
        }

        ApplicationManager.getApplication().invokeLater {
            try {
                file.inputStream.use { inputStream ->
                    val compose = parser.parse(inputStream)
                    if (compose == null) {
                        logger.info("Failed to parse docker-compose file: ${file.name}")
                        return@invokeLater
                    }

                    val databases = parser.extractDatabaseConnections(compose)
                    logger.info("Parsed ${file.name}: found ${databases.size} database service(s)")

                    if (databases.isEmpty()) {
                        logger.info("No database services found in ${file.name}, skipping data source creation")
                        return@invokeLater
                    }

                    databases.forEach { db ->
                        logger.info("  - ${db.serviceName} (${db.databaseType}) on port ${db.port}")
                    }

                    creator.createOrUpdateDataSources(project, databases, file)
                }
            } catch (e: Exception) {
                logger.warn("Failed to process docker-compose file change: ${file.path}", e)
            }
        }
    }

    private fun calculateDepth(basePath: String, filePath: String): Int {
        if (!filePath.startsWith(basePath)) {
            return Int.MAX_VALUE
        }

        val relativePath = filePath.substring(basePath.length).trimStart('/')
        if (relativePath.isEmpty()) {
            return 0
        }

        // Count directory separators to determine depth
        // File at root = depth 0
        // File in subdirectory = depth 1 (one separator before filename)
        val parts = relativePath.split('/')
        return parts.size - 1  // Subtract 1 because the last part is the filename
    }
}
