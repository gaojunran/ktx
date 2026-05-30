package io.github.nebula.ktx.cli.meta

import java.nio.file.Path
import kotlin.io.path.bufferedReader

/**
 * 脚本顶部声明的工具链信息。空字段表示用户未指定，沿用默认值。
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
 * 在「调编译器之前」用纯文本扫描脚本头部，找出 `@file:Toolchain(...)`。
 *
 * 这是「先有鸡还是先有蛋」问题的解法：注解里声明的 kotlin 版本本身决定
 * 用哪个 Kotlin 编译器去编译这个脚本，所以读取必须独立于编译器。
 *
 * 扫描规则：
 *   - shebang（首行 `#!`）跳过。
 *   - 空行、`//` 行注释、`/* ... */` 块注释跳过。
 *   - 多个 `@file:` 注解可在 head 区域连续出现。
 *   - 一旦遇到 head 区域之外的行（普通代码、import 等），扫描停止。
 *
 * 我们 **不** 试图实现一个完整 Kotlin 解析器：保守地按行处理，
 * 失败时回退到「未声明 Toolchain」即可，实际负担由编译器再报。
 *
 * 解析的容错性：`@file:Toolchain` 内部允许跨多行（虽然样例脚本一般写一行），
 * 通过括号配对累计到完整一段后再做 KV 解析。
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

            // Shebang 仅出现在第一行；这里用宽松检查，第二行起的 #! 没有意义但也不害事。
            if (line.startsWith("#!")) continue

            // 块注释跨行处理。
            var cursor = line
            if (inBlockComment) {
                val end = cursor.indexOf("*/")
                if (end < 0) continue
                cursor = cursor.substring(end + 2).trimStart()
                inBlockComment = false
            }
            // 同行内可能有多段块注释，朴素处理一段就够：再遇到嵌套就放弃扫描。
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
                // 其他 @file: 注解（DependsOn 等）——继续扫，不打断。
                continue
            }

            // 真正的代码开始，head 区域结束。
            break
        }

        return ScriptToolchain.EMPTY
    }

    // 移除当前行内成对出现的块注释（slash-star 到 star-slash）；
    // 遇到不闭合的开符号时通过回调标记进入块注释。
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
