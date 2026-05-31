package io.github.nebula.ktx.toolchain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformTest {

    @Test
    fun `current detects something sensible`() {
        val p = Platform.current()
        // Mainly assert that this doesn't throw — at least one enum value matches the host
        assertTrue(p.os in Platform.Os.entries)
        assertTrue(p.arch in Platform.Arch.entries)
    }

    @Test
    fun `mac javaBinRelative includes Contents Home`() {
        val p = Platform(Platform.Os.MAC, Platform.Arch.AARCH64)
        assertEquals("Contents/Home/bin/java", p.javaBinRelative())
    }

    @Test
    fun `linux javaBinRelative is bin slash java`() {
        val p = Platform(Platform.Os.LINUX, Platform.Arch.X64)
        assertEquals("bin/java", p.javaBinRelative())
    }

    @Test
    fun `windows javaBinRelative uses backslash and exe`() {
        val p = Platform(Platform.Os.WINDOWS, Platform.Arch.X64)
        assertEquals("bin\\java.exe", p.javaBinRelative())
    }

    @Test
    fun `archive suffix matches os`() {
        assertEquals(".tar.gz", Platform(Platform.Os.MAC, Platform.Arch.AARCH64).archiveSuffix)
        assertEquals(".tar.gz", Platform(Platform.Os.LINUX, Platform.Arch.X64).archiveSuffix)
        assertEquals(".zip", Platform(Platform.Os.WINDOWS, Platform.Arch.X64).archiveSuffix)
    }
}
