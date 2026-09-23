package de.sandstorm.databasehelpers.docker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parsing of the `.env` file docker compose reads from the directory of the compose file.
 */
class DotEnvTest {

    private fun parse(text: String): Map<String, String> = DotEnv.parse(text.trimIndent())

    @Test
    fun `assignments become variables`() {
        assertEquals(mapOf("DB_PORT" to "15432"), parse("DB_PORT=15432"))
    }

    @Test
    fun `comments, blank lines and export prefixes are not variables`() {
        assertEquals(
            mapOf("DB_USER" to "neos", "DB_PASSWORD" to "secret"),
            parse(
                """
                # database credentials

                DB_USER=neos
                  export DB_PASSWORD = secret
                # DB_USER=commented-out
                not-an-assignment
                """
            )
        )
    }

    @Test
    fun `quotes protect a value and are stripped`() {
        assertEquals(
            mapOf(
                "SPACED" to "a value",
                "HASHED" to "pass#word",
                "SINGLE" to "it's fine",
                "EMPTY" to ""
            ),
            parse(
                """
                SPACED="a value"
                HASHED="pass#word"
                SINGLE='it's fine'
                EMPTY=
                """
            )
        )
    }

    @Test
    fun `an unquoted value loses a trailing inline comment`() {
        assertEquals(mapOf("DB_PORT" to "15432"), parse("DB_PORT=15432 # the published port"))
    }
}
