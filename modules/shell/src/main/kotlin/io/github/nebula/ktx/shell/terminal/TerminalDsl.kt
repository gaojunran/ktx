package io.github.nebula.ktx.shell.terminal

import com.github.ajalt.mordant.animation.progress.BlockingAnimator
import com.github.ajalt.mordant.animation.progress.advance
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.update
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.VerticalAlign
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.terminal.StringPrompt
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.progress.ProgressLayoutBuilder
import com.github.ajalt.mordant.widgets.progress.completed
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text

/**
 * The default terminal instance used by ktx shell DSL.
 */
val terminal: Terminal = Terminal()

// ------------------------------------------------------------------
// Logging helpers
// ------------------------------------------------------------------

fun log(message: String) {
    terminal.println(message)
}

fun logStep(first: String, rest: String = "") {
    val styled = if (rest.isEmpty()) {
        TextColors.green(first)
    } else {
        "${TextColors.green(first)} $rest"
    }
    terminal.println(styled)
}

fun logError(message: String) {
    terminal.println("${TextColors.red("Error")} $message")
}

fun logWarn(message: String) {
    terminal.println("${TextColors.yellow("Warning")} $message")
}

fun logLight(message: String) {
    terminal.println(TextColors.gray(message))
}

// ------------------------------------------------------------------
// Prompts
// ------------------------------------------------------------------

/**
 * Ask the user a yes/no question.
 *
 * ```kotlin
 * val ok = confirm("Would you like to continue?")
 * val ok2 = confirm("Would you like to continue?", default = true)
 * ```
 */
fun confirm(message: String, default: Boolean = false): Boolean {
    return YesNoPrompt(message, terminal, default = default).ask() ?: default
}

/**
 * Prompt the user for a string value.
 *
 * ```kotlin
 * val name = prompt("What's your name?")
 * val name2 = prompt("What's your name?", default = "Dax")
 * ```
 */
fun prompt(message: String, default: String? = null): String {
    return if (default != null) {
        StringPrompt(message, terminal, default = default).ask() ?: default
    } else {
        StringPrompt(message, terminal).ask() ?: ""
    }
}

/**
 * Prompt the user for a password (masked input).
 *
 * ```kotlin
 * val password = promptSecret("What's your password?")
 * ```
 */
fun promptSecret(message: String): String {
    return StringPrompt(message, terminal, hideInput = true).ask() ?: ""
}

/**
 * Present a single-choice selection to the user.
 *
 * ```kotlin
 * val env = select("Where to?", listOf("staging", "production"))
 * ```
 */
fun <T> select(message: String, options: List<T>): T {
    if (options.isEmpty()) throw IllegalArgumentException("options must not be empty")

    terminal.println(message)
    options.forEachIndexed { i, opt ->
        terminal.println("  ${i + 1}. $opt")
    }
    while (true) {
        val input = StringPrompt("Select (1-${options.size})", terminal).ask() ?: continue
        val index = input.toIntOrNull()?.let { it - 1 }
        if (index != null && index in options.indices) {
            return options[index]
        }
        terminal.println(TextColors.red("Invalid selection. Please enter a number between 1 and ${options.size}."))
    }
}

/**
 * Present a multi-choice selection to the user.
 *
 * ```kotlin
 * val choices = multiSelect(
 *     "Which days?",
 *     listOf("Mon", "Tue", "Wed"),
 *     default = setOf("Mon")
 * )
 * ```
 */
fun <T> multiSelect(
    message: String,
    options: List<T>,
    default: Set<T> = emptySet(),
): List<T> {
    if (options.isEmpty()) return emptyList()

    val defaultIndices = default.mapNotNull { options.indexOf(it) }.toSet()
    val selected = defaultIndices.toMutableSet()

    terminal.println(message)
    terminal.println("Enter numbers separated by commas (e.g. 1,3), or 'all' / 'none'.")

    options.forEachIndexed { i, opt ->
        val marker = if (i in selected) "[x]" else "[ ]"
        terminal.println("  $marker ${i + 1}. $opt")
    }

    while (true) {
        val input = StringPrompt("Select", terminal).ask() ?: continue
        when (input.lowercase()) {
            "all" -> return options
            "none" -> return emptyList()
            else -> {
                val indices = input.split(",")
                    .mapNotNull { it.trim().toIntOrNull()?.let { n -> n - 1 } }
                    .filter { it in options.indices }
                if (indices.isNotEmpty()) {
                    return indices.map { options[it] }
                }
                terminal.println(TextColors.red("Invalid selection."))
            }
        }
    }
}

// ------------------------------------------------------------------
// Progress
// ------------------------------------------------------------------

/**
 * Run a block with an indeterminate progress spinner.
 *
 * ```kotlin
 * progress("Updating Database") {
 *     // do some work
 * }
 * ```
 */
fun <T> progress(message: String, block: () -> T): T {
    val definition = ProgressLayoutBuilder<Unit>().apply {
        text(message)
        spinner(Spinner.Dots())
    }.build()
    val animator = definition.animateOnThread(terminal, context = Unit, total = null)
    animator.runBlocking()
    return try {
        block()
    } finally {
        animator.stop()
    }
}

/**
 * Run a block with a determinate progress bar.
 *
 * ```kotlin
 * progress("Type-checking", length = files.size) { bar ->
 *     for (file in files) {
 *         typeCheck(file)
 *         bar.increment()
 *     }
 * }
 * ```
 */
fun <T> progress(message: String, length: Long, block: (ProgressBar) -> T): T {
    val definition = ProgressLayoutBuilder<Unit>().apply {
        text(message)
        progressBar()
        percentage()
        completed()
    }.build()
    val animator = definition.animateOnThread(terminal, context = Unit, total = length)
    animator.runBlocking()
    val bar = object : ProgressBar {
        override fun increment(n: Long) {
            animator.advance(n)
        }

        override fun update(current: Long) {
            animator.update { completed = current }
        }
    }
    return try {
        block(bar)
    } finally {
        animator.stop()
    }
}

interface ProgressBar {
    fun increment(n: Long = 1)
    fun update(current: Long)
}

// ------------------------------------------------------------------
// Colors (re-export for convenience)
// ------------------------------------------------------------------

fun green(text: String): String = TextColors.green(text)
fun red(text: String): String = TextColors.red(text)
fun yellow(text: String): String = TextColors.yellow(text)
fun gray(text: String): String = TextColors.gray(text)
fun blue(text: String): String = TextColors.blue(text)
fun bold(text: String): String = TextStyles.bold(text)
