package io.github.nebula.ktx.shell

/**
 * Wrapper for an argument that should NOT be shell-escaped.
 *
 * ```kotlin
 * sh"echo ${RawArg(rawFlags)} ${safeName}"
 * ```
 */
class RawArg(val value: String) {
    override fun toString(): String = value
}
