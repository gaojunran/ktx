package io.github.nebula.ktx.shell

/**
 * Shell argument escape logic: follows POSIX sh quoting rules.
 *
 * - If a string contains no special characters, it is returned as-is.
 * - Otherwise it is single-quoted, and any internal `'` is replaced by `'\''`.
 */
object ShellEscape {
    private val SAFE = Regex("""^[a-zA-Z0-9._/@:=,-]+$""")

    fun escape(arg: String): String {
        if (SAFE.matches(arg)) return arg
        return "'" + arg.replace("'", "'\"'\"'") + "'"
    }
}
