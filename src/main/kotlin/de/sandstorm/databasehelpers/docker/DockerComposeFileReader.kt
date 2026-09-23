package de.sandstorm.databasehelpers.docker

import com.intellij.openapi.vfs.VirtualFile

/**
 * Reads the databases out of a compose file on disk, resolving everything that depends on the
 * file's location: the `.env` file beside it that supplies placeholder values, and the
 * `env_file` entries its services point at.
 */
class DockerComposeFileReader(private val parser: DockerComposeParser = DockerComposeParser()) {

    fun readDatabases(file: VirtualFile): List<DatabaseInfo> {
        val directory = file.parent
        val compose = file.inputStream.use { parser.parse(it, interpolationVariables(directory)) }
            ?: return emptyList()
        return parser.extractDatabaseConnections(compose) { path ->
            directory?.findFileByRelativePath(path)?.let { textOf(it) }
        }
    }

    /**
     * Compose resolves `${...}` against the `.env` file beside the compose file, with the real
     * environment taking precedence over it.
     */
    private fun interpolationVariables(directory: VirtualFile?): Map<String, String> {
        val dotEnv = directory?.findChild(".env")?.let { DotEnv.parse(textOf(it)) }.orEmpty()
        return dotEnv + System.getenv()
    }

    private fun textOf(file: VirtualFile): String = String(file.contentsToByteArray(), file.charset)
}
