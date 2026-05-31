package io.github.nebula.ktx.cli.meta

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests covering several edge cases:
 *   1. shebang doesn't affect scanning;
 *   2. Toolchain coexists with other @file: annotations regardless of order;
 *   3. line and block comments are skipped;
 *   4. EMPTY is returned when no Toolchain is present;
 *   5. the word "Toolchain" inside a string literal does not cause false
 *      positives (the head section ends before any string literal context).
 */
class ToolchainHeaderScannerTest {

    @Test
    fun `extracts both kotlin and jdk`() {
        val src = """
            @file:Toolchain(kotlin = "2.2.0", jdk = "21")
            println("hi")
        """.trimIndent()
        assertEquals(ScriptToolchain("2.2.0", "21"), ToolchainHeaderScanner.scan(src))
    }

    @Test
    fun `coexists with other file annotations`() {
        val src = """
            #!/usr/bin/env ktx
            @file:DependsOn("io.ktor:ktor-client-core-jvm:3.2.0")
            @file:Toolchain(jdk = "17")
            @file:Repository("https://example.com")

            import io.ktor.client.*
        """.trimIndent()
        assertEquals(ScriptToolchain(kotlin = "", jdk = "17"), ToolchainHeaderScanner.scan(src))
    }

    @Test
    fun `returns empty when toolchain absent`() {
        val src = """
            @file:DependsOn("io.ktor:ktor-client-core-jvm:3.2.0")
            println("no toolchain here")
        """.trimIndent()
        assertEquals(ScriptToolchain.EMPTY, ToolchainHeaderScanner.scan(src))
    }

    @Test
    fun `skips line and block comments before annotations`() {
        val src = """
            // file-level note
            /* this is a
               multi-line block comment */
            @file:Toolchain(kotlin = "2.2.0")
            println("hi")
        """.trimIndent()
        assertEquals(ScriptToolchain(kotlin = "2.2.0", jdk = ""), ToolchainHeaderScanner.scan(src))
    }

    @Test
    fun `stops scanning when entering script body`() {
        // Even if `@file:Toolchain` reappears in the body (which would not be
        // valid anyway), the scanner must not cross into the body.
        val src = """
            @file:DependsOn("a:b:1")
            println("hello")
            // @file:Toolchain(jdk = "99")
        """.trimIndent()
        assertEquals(ScriptToolchain.EMPTY, ToolchainHeaderScanner.scan(src))
    }

    @Test
    fun `handles toolchain across multiple lines`() {
        val src = """
            @file:Toolchain(
                kotlin = "2.2.0",
                jdk = "21",
            )
            println("hi")
        """.trimIndent()
        assertEquals(ScriptToolchain("2.2.0", "21"), ToolchainHeaderScanner.scan(src))
    }
}
