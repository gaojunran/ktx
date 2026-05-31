package io.github.nebula.ktx.cli.meta

import java.nio.file.Path
import kotlin.io.path.bufferedReader

/**
 * Toolchain info declared at the top of a script. Empty fields mean the user
 * did not specify them; defaults apply.
 */
data class ScriptToolchain(
    val kotlin: String,
    val jdk: String,
) {
    companion object {
        val EMPTY = ScriptToolchain(kotlin = "", jdk = "")
    }
}

/**
 * Plain-text scan of a script header to find `@file:Toolchain(...)`,
 * performed **before invoking the compiler**.
 *
 * This solves a chicken-and-egg problem: the kotlin version declared in the
 * annotation itself decides which Kotlin compiler to use, so reading it must
 * be independent of the compiler.
 *
 * Scanning rules:
 *   - shebang (first-line `#!`) is skipped;
 *   - blank lines, `//` line comments, and `/* ... */` block comments are skipped;
 *   - multiple `@file:` annotations may appear consecutively in the header;
 *   - once a non-header line (regular code, import, etc.) is encountered,
 *     scanning stops.
 *
 * We **do not** attempt a full Kotlin parser: we process line by line with a
 * conservative strategy, falling back to "no Toolchain declared" on failure
 * and letting the compiler report any actual issues.
 *
 * Forgiving parsing: `@file:Toolchain` may span multiple lines (though sample
 * scripts usually fit on one). We accumulate by paren matching until a complete
 * segment is collected before doing the KV parse.
 */
object ToolchainHeaderScanner {

    private val TOOLCHAIN_START = Regex("""@file\s*:\s*Toolchain\s*\(""")
    private val KV = Regex("""(\w+)\s*=\s*"([^"]*)"""")

    fun scan(scriptPath: Path): ScriptToolchain {
        scriptPath.bufferedReader().use { reader ->
            return scan(reader.readText())
        }
    }

    fun scan(source: String): ScriptToolchain {
        val lines = source.lines().iterator()
        var inBlockComment = false
        var pendingAnnotation: StringBuilder? = null
        var openParens = 0

        while (lines.hasNext()) {
            val raw = lines.next()
            val line = raw.trim()

            // Shebang only appears on the first line; the loose check here is harmless on later lines.
            if (line.startsWith("#!")) continue

            // Multi-line block comment handling.
            var cursor = line
            if (inBlockComment) {
                val end = cursor.indexOf("*/")
                if (end < 0) continue
                cursor = cursor.substring(end + 2).trimStart()
                inBlockComment = false
            }
            // A line may contain multiple block comments; one pass is enough. Give up on nested ones.
            cursor = stripInlineBlockComment(cursor) { inBlockComment = true } ?: continue

            if (cursor.isEmpty()) continue
            if (cursor.startsWith("//")) continue

            if (pendingAnnotation != null) {
                pendingAnnotation.append(' ').append(cursor)
                openParens += cursor.count { it == '(' } - cursor.count { it == ')' }
                if (openParens <= 0) {
                    parseToolchain(pendingAnnotation.toString())?.let { return it }
                    pendingAnnotation = null
                    openParens = 0
                }
                continue
            }

            if (TOOLCHAIN_START.containsMatchIn(cursor)) {
                pendingAnnotation = StringBuilder(cursor)
                openParens = cursor.count { it == '(' } - cursor.count { it == ')' }
                if (openParens <= 0) {
                    parseToolchain(pendingAnnotation.toString())?.let { return it }
                    pendingAnnotation = null
                    openParens = 0
                }
                continue
            }

            if (cursor.startsWith("@file:")) {
                // Other @file: annotations (DependsOn etc.); keep scanning.
                continue
            }

            // Real code begins, header section ends.
            break
        }

        return ScriptToolchain.EMPTY
    }

    // Strip paired block comments (slash-star to star-slash) on the current line;
    // signal an unmatched opener via the callback to enter block-comment mode.
    private fun stripInlineBlockComment(line: String, onBlockOpen: () -> Unit): String? {
        var s = line
        while (true) {
            val open = s.indexOf("/*")
            if (open < 0) return s
            val close = s.indexOf("*/", open + 2)
            if (close < 0) {
                onBlockOpen()
                val prefix = s.substring(0, open).trim()
                return if (prefix.isEmpty()) null else prefix
            }
            s = (s.substring(0, open) + " " + s.substring(close + 2)).trim()
        }
    }

    private fun parseToolchain(annotation: String): ScriptToolchain? {
        val openIdx = annotation.indexOf('(')
        val closeIdx = annotation.lastIndexOf(')')
        if (openIdx < 0 || closeIdx < openIdx) return null
        val body = annotation.substring(openIdx + 1, closeIdx)

        var kotlin = ""
        var jdk = ""
        for (m in KV.findAll(body)) {
            when (m.groupValues[1]) {
                "kotlin" -> kotlin = m.groupValues[2]
                "jdk" -> jdk = m.groupValues[2]
            }
        }
        if (kotlin.isEmpty() && jdk.isEmpty()) return null
        return ScriptToolchain(kotlin = kotlin, jdk = jdk)
    }
}
