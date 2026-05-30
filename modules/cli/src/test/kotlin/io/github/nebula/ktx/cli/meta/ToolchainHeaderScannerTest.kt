package io.github.nebula.ktx.cli.meta

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 单测覆盖几个关键边界：
 *   1. shebang 不影响扫描；
 *   2. Toolchain 注解可与其他 @file: 注解共存且顺序无关；
 *   3. 行注释 / 块注释跳过；
 *   4. 没有 Toolchain 时返回 EMPTY；
 *   5. 字符串里出现 "Toolchain" 不会误伤（因为 head 区域结束前尚未进入字符串字面量场景）。
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
            // 文件级说明
            /* 这里有一段
               跨行块注释 */
            @file:Toolchain(kotlin = "2.2.0")
            println("hi")
        """.trimIndent()
        assertEquals(ScriptToolchain(kotlin = "2.2.0", jdk = ""), ToolchainHeaderScanner.scan(src))
    }

    @Test
    fun `stops scanning when entering script body`() {
        // 即使代码体里再写 @file:Toolchain（实际写不写都不合法），扫描器也不应越过 head。
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
