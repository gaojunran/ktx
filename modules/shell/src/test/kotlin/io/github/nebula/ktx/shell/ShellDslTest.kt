package io.github.nebula.ktx.shell

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ShellDslTest {

    @Test
    fun `sh with simple command returns text`() {
        val result = runBlocking {
            sh("echo", "hello").text()
        }
        assertEquals("hello\n", result)
    }

    @Test
    fun `sh with argument containing spaces escapes correctly`() {
        val result = runBlocking {
            sh("echo", "hello world").text()
        }
        assertEquals("hello world\n", result)
    }

    @Test
    fun `String dot sh extension works`() {
        val result = runBlocking {
            "echo".sh("from extension").text()
        }
        assertEquals("from extension\n", result)
    }

    @Test
    fun `RawArg bypasses escaping`() {
        val result = runBlocking {
            sh("printf", RawArg("%s"), "hello").text()
        }
        assertEquals("hello", result)
    }

    @Test
    fun `lines splits stdout into lines`() {
        val result = runBlocking {
            sh("echo", "one && echo two").lines()
        }
        assertEquals(listOf("one && echo two"), result)
    }

    @Test
    fun `multi-line output splits correctly`() {
        val result = runBlocking {
            sh("sh", "-c", "echo one; echo two; echo three").lines()
        }
        assertEquals(listOf("one", "two", "three"), result)
    }

    @Test
    fun `bytes returns raw stdout`() {
        val result = runBlocking {
            sh("echo", "hello").bytes()
        }
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `exitCode returns exit code`() {
        val code = runBlocking {
            sh("sh", "-c", "exit 42").exitCode()
        }
        assertEquals(42, code)
    }

    @Test
    fun `non-zero exit throws by default`() {
        val ex = assertFailsWith<ShellException> {
            runBlocking {
                sh("sh", "-c", "exit 123").execute()
            }
        }
        assertEquals(123, ex.result.exitCode)
    }

    @Test
    fun `ignoreExitCode prevents throwing`() {
        val result = runBlocking {
            sh("sh", "-c", "exit 1").ignoreExitCode().execute()
        }
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `quiet suppresses output`() {
        val result = runBlocking {
            sh("echo", "hello").quiet().text()
        }
        assertEquals("", result)
    }

    @Test
    fun `env sets environment variable`() {
        val result = runBlocking {
            sh("sh", "-c", "echo \$TEST_VAR")
                .env("TEST_VAR", "test_value")
                .text()
        }
        assertEquals("test_value\n", result)
    }

    @Test
    fun `cwd changes working directory`() {
        val result = runBlocking {
            sh("pwd").cwd("/tmp").text().trim()
        }
        // macOS symlinks /tmp to /private/tmp
        assertTrue(result == "/tmp" || result == "/private/tmp")
    }

    @Test
    fun `withTimeout throws on timeout`() {
        val ex = assertFailsWith<ShellTimeoutException> {
            runBlocking {
                sh("sleep", "5").withTimeout(50.milliseconds).execute()
            }
        }
        assertTrue(ex.message!!.contains("timed out"))
    }

    @Test
    fun `pipe connects stdout to stdin`() {
        val result = runBlocking {
            (sh("echo", "hello") pipe sh("cat")).text()
        }
        assertEquals("hello\n", result)
    }

    @Test
    fun `shAll runs commands concurrently`() {
        val start = System.currentTimeMillis()
        val results = runBlocking {
            shAll(
                sh("sh", "-c", "echo one; sleep 0.1"),
                sh("sh", "-c", "echo two; sleep 0.1"),
            )
        }
        val elapsed = System.currentTimeMillis() - start
        assertEquals(2, results.size)
        assertEquals("one\n", results[0].stdout)
        assertEquals("two\n", results[1].stdout)
        assertTrue(elapsed < 300, "should run concurrently, took $elapsed ms")
    }

    @Test
    fun `retry succeeds on first attempt`() {
        var calls = 0
        val result = runBlocking {
            retry {
                calls++
                sh("echo", "ok").text()
            }
        }
        assertEquals("ok\n", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retry retries on failure`() {
        var calls = 0
        val ex = assertFailsWith<ShellException> {
            runBlocking {
                retry(times = 3) {
                    calls++
                    sh("sh", "-c", "exit 1").execute()
                }
            }
        }
        assertEquals(3, calls)
    }

    @Test
    fun `shell block inherits workingDir from cd`() {
        val result = runBlocking {
            shell {
                cd("/tmp")
                "pwd"().text().trim()
            }
        }
        assertTrue(result == "/tmp" || result == "/private/tmp")
    }

    @Test
    fun `shell block inherits env from scope`() {
        val result = runBlocking {
            shell {
                env("MY_VAR", "scope_value")
                "env"().text()
            }
        }
        assertTrue(result.contains("MY_VAR=scope_value"))
    }

    @Test
    fun `command env overrides scope env`() {
        val result = runBlocking {
            shell {
                env("MY_VAR", "scope_value")
                "env"().env("MY_VAR", "cmd_value").text()
            }
        }
        assertTrue(result.contains("MY_VAR=cmd_value"))
        assertTrue(!result.contains("MY_VAR=scope_value"))
    }
}
