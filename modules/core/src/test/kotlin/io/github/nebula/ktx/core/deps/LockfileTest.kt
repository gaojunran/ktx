package io.github.nebula.ktx.core.deps

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LockfileTest {

    @Test
    fun `roundtrip preserves directs and repositories`(@TempDir tmp: Path) {
        val lf = Lockfile(
            scriptHash = "abc123",
            directs = mapOf(
                "io.ktor:ktor-client-core-jvm:3.2.0" to listOf(
                    "io/ktor/ktor-client-core-jvm/3.2.0/ktor-client-core-jvm-3.2.0.jar",
                    "io/ktor/ktor-io-jvm/3.2.0/ktor-io-jvm-3.2.0.jar",
                ),
                "com.example:foo:1.0" to listOf("com/example/foo/1.0/foo-1.0.jar"),
            ),
            repositories = listOf("https://example.com/maven"),
        )
        val path = tmp / "x.kts.lock"
        lf.write(path)
        val read = Lockfile.read(path)!!
        assertEquals(lf.scriptHash, read.scriptHash)
        assertEquals(lf.directs, read.directs)
        assertEquals(lf.repositories, read.repositories)
    }

    @Test
    fun `read returns null for missing file`(@TempDir tmp: Path) {
        assertNull(Lockfile.read(tmp / "nonexistent.lock"))
    }

    @Test
    fun `pathFor sits next to script`() {
        val script = Path.of("/path/to/foo.main.kts")
        val lock = Lockfile.pathFor(script)
        assertEquals("foo.main.kts.lock", lock.fileName.toString())
        assertEquals(script.parent, lock.parent)
    }
}
