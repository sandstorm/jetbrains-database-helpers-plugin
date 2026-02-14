package de.sandstorm.databasehelpers.docker

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile

class DockerComposeStartupActivity : ProjectActivity, DumbAware {
    private val logger = Logger.getInstance(DockerComposeStartupActivity::class.java)
    private val parser = DockerComposeParser()
    private val creator = DataSourceCreator()

    override suspend fun execute(project: Project) {
        logger.info("Scanning for docker-compose files in project: ${project.name}")

        val composeFiles = findDockerComposeFiles(project)
        logger.info("Found ${composeFiles.size} docker-compose file(s)")

        composeFiles.forEach { file ->
            processDockerComposeFile(project, file)
        }
    }

    private suspend fun findDockerComposeFiles(project: Project): List<VirtualFile> {
        return readAction {
            val files = mutableListOf<VirtualFile>()

            try {
                // Scan project base directory and subdirectories (max 2 levels deep)
                val baseDir = project.baseDir
                if (baseDir != null) {
                    logger.warn("Scanning base directory: ${baseDir.path} (max depth: 2)")
                    scanDirectoryForDockerCompose(baseDir, files, currentDepth = 0, maxDepth = 2)
                } else {
                    logger.warn("Project base directory is null for project: ${project.name}")
                }
            } catch (e: Exception) {
                logger.error("Error finding docker-compose files", e)
            }

            val result = files.distinct()
            logger.warn("Total docker-compose files found: ${result.size}")
            result.forEach { file ->
                logger.warn("  - ${file.path}")
            }
            result
        }
    }

    private fun scanDirectoryForDockerCompose(
        dir: VirtualFile,
        files: MutableList<VirtualFile>,
        currentDepth: Int,
        maxDepth: Int
    ) {
        dir.children.forEach { file ->
            if (file.isDirectory && !file.name.startsWith(".")) {
                // Recursively scan subdirectories up to maxDepth levels
                if (currentDepth < maxDepth) {
                    logger.warn("Scanning subdirectory: ${file.name} (depth: ${currentDepth + 1})")
                    scanDirectoryForDockerCompose(file, files, currentDepth + 1, maxDepth)
                } else {
                    logger.warn("Skipping directory ${file.name} (max depth $maxDepth reached)")
                }
            } else if (file.name.matches(Regex("docker-compose.*\\.ya?ml"))) {
                logger.warn("Found docker-compose file: ${file.path} (depth: $currentDepth)")
                files.add(file)
            }
        }
    }

    private fun processDockerComposeFile(project: Project, file: VirtualFile) {
        try {
            logger.info("Processing docker-compose file: ${file.path}")

            file.inputStream.use { inputStream ->
                val compose = parser.parse(inputStream) ?: return
                val databases = parser.extractDatabaseConnections(compose)

                logger.info("Found ${databases.size} database service(s) in ${file.name}")
                databases.forEach { db ->
                    logger.info("  - ${db.serviceName} (${db.databaseType}) on port ${db.port}")
                }

                creator.createOrUpdateDataSources(project, databases, file)
            }
        } catch (e: Exception) {
            logger.warn("Failed to process docker-compose file: ${file.path}", e)
        }
    }
}
