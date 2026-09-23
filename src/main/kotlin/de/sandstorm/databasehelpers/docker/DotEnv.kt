package de.sandstorm.databasehelpers.docker

/**
 * The `.env` file docker compose reads from the directory holding the compose file, and which
 * supplies the values for `${...}` placeholders in it.
 */
object DotEnv {

    fun parse(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("#") }
            .mapNotNull { line ->
                val (name, value) = line.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                name.removePrefix("export ").trim() to unquote(value.trim())
            }
            .toMap()

    /**
     * Quotes make a value literal: they are stripped, and only an unquoted value can carry a
     * trailing `#` comment - a quoted one may legitimately contain a hash.
     */
    private fun unquote(value: String): String {
        val quote = value.firstOrNull()
        if ((quote == '"' || quote == '\'') && value.length >= 2 && value.last() == quote) {
            return value.substring(1, value.length - 1)
        }
        return value.substringBefore(" #").trim()
    }
}
