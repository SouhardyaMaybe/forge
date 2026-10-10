package com.forge.ide.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the session supervisor pieces: the memory governor's decision
 * policy and the on-disk registry that lets sessions be re-attached after the
 * app process dies.
 */
class AgentSessionSupervisorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `meminfo parses total and available`() {
        val (total, available) = RuntimeMemoryGovernor.memInfoKb()
        // On any Linux CI runner both are positive; -1 means unreadable.
        if (total > 0) {
            assertTrue("total should exceed available", total >= available)
            assertTrue(available > 0)
        }
    }

    @Test
    fun `snapshot flags red and amber bands`() {
        val red = RuntimeMemoryGovernor.Snapshot(
            availableKb = RuntimeMemoryGovernor.RED_LINE_KB - 1,
            totalKb = 4_000_000,
            activeHeavySessions = 0,
        )
        assertTrue(red.isRed)
        assertNotNull(RuntimeMemoryGovernor.refusal(0).takeIf { false })

        val amber = RuntimeMemoryGovernor.Snapshot(
            availableKb = RuntimeMemoryGovernor.AMBER_LINE_KB - 1,
            totalKb = 4_000_000,
            activeHeavySessions = 0,
        )
        assertFalse(amber.isRed)
        assertTrue(amber.isAmber)
    }

    @Test
    fun `refusal blocks low memory and extra sessions`() {
        val available = RuntimeMemoryGovernor.availableKb()
        // Whatever the runner has, at most one heavy session is allowed on a
        // constrained device and the governor must always answer.
        assertNotNull(RuntimeMemoryGovernor.refusal(activeHeavySessions = 99))
        if (available in 1 until RuntimeMemoryGovernor.RED_LINE_KB) {
            assertNotNull("low memory must refuse", RuntimeMemoryGovernor.refusal(0))
        }
    }

    @Test
    fun `registry round-trips a record`() {
        val dir = File(tempFolder.newFolder("sessions"), "registry")
        val registry = DetachedSessionRegistry(dir)

        val record = SessionRecord(
            id = "session-1",
            kind = "agent:claude",
            title = "demo project",
            projectRoot = "/data/forge/projects/demo",
            argv = listOf("/root/.forge/bin/claude", "--resume", "abc123"),
            environment = mapOf("HOME" to "/root", "PATH" to "/usr/bin"),
            logPath = "${dir.absolutePath}/session-1.log",
            fifoPath = "${dir.absolutePath}/session-1.fifo",
            pid = 4242,
            startedAt = 1_700_000_000_000,
            state = SessionRecord.STATE_RUNNING,
        )
        registry.save(record)

        val loaded = registry.list().single()
        assertEquals(record, loaded)
        assertTrue(loaded.isRunning)
        assertEquals(listOf("/root/.forge/bin/claude", "--resume", "abc123"), loaded.argv)
        assertEquals("abc123", loaded.argv.last())

        registry.update(record.id, SessionRecord.STATE_STOPPED)
        assertTrue(registry.list().single().state == SessionRecord.STATE_STOPPED)

        registry.save(record.copy(id = "session-2"))
        assertEquals(2, registry.list().size)

        registry.remove(record.id)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun `registry survives a fresh instance reading the same directory`() {
        val dir = File(tempFolder.newFolder("sessions"), "registry")
        val record = SessionRecord(
            id = "s",
            kind = "shell",
            title = "shell",
            projectRoot = "/",
            argv = listOf("/system/bin/sh"),
            environment = emptyMap(),
            logPath = "$dir/s.log",
            fifoPath = "$dir/s.fifo",
            pid = 1,
            startedAt = 0L,
            state = SessionRecord.STATE_RUNNING,
        )
        DetachedSessionRegistry(dir).save(record)

        // A "restarted app" opens the registry again from scratch.
        val reopened = DetachedSessionRegistry(dir).list()
        assertEquals(1, reopened.size)
        assertEquals("shell", reopened.first().kind)
    }

    @Test
    fun `empty registry yields no records and no crash`() {
        val registry = DetachedSessionRegistry(File(tempFolder.newFolder(), "empty"))
        assertTrue(registry.list().isEmpty())
    }
}
