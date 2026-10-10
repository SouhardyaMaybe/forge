package com.forge.ide.runtime

import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Memory guard for heavy sessions.
 *
 * Agent CLIs (Claude Code, DeepSeek Harness, Antigravity) and Gradle builds are
 * the two things that drive a 3 GB phone into the low-memory killer, and a kill
 * is what users experience as "the server restarted". This governor refuses to
 * start another heavy session while the device is under pressure, and tells the
 * UI why, instead of letting the OS make the decision.
 */
object RuntimeMemoryGovernor {

    private const val TAG = "ForgeGovernor"

    /** Below this much available memory we do not start anything heavy. */
    const val RED_LINE_KB: Long = 350L * 1024L

    /** Below this much we warn before starting a heavy session. */
    const val AMBER_LINE_KB: Long = 600L * 1024L

    /** Heavy sessions that may run at once on a low-memory device. */
    const val MAX_CONCURRENT_LOW_MEM = 1

    data class Snapshot(
        val availableKb: Long,
        val totalKb: Long,
        val activeHeavySessions: Int,
    ) {
        val isRed: Boolean get() = availableKb in 1 until RED_LINE_KB
        val isAmber: Boolean get() = availableKb in 1 until AMBER_LINE_KB
    }

    /** Reads MemTotal/MemAvailable from /proc/meminfo; -1 when unreadable. */
    fun memInfoKb(): Pair<Long, Long> {
        var total = -1L
        var available = -1L
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("MemTotal:") -> total = line.kbValue()
                    line.startsWith("MemAvailable:") -> available = line.kbValue()
                }
            }
        }
        return total to available
    }

    fun availableKb(): Long = memInfoKb().second

    fun snapshot(activeHeavySessions: Int = 0): Snapshot {
        val (total, available) = memInfoKb()
        return Snapshot(available, total, activeHeavySessions)
    }

    /**
     * Decides whether another heavy session may start.
     *
     * @return null when allowed, otherwise a user-visible reason.
     */
    fun refusal(activeHeavySessions: Int): String? {
        val available = availableKb()
        if (available in 1 until RED_LINE_KB) {
            return "Low memory (${available / 1024} MB free). Close something before starting another agent or build."
        }
        val limit = if (available in 1 until AMBER_LINE_KB) MAX_CONCURRENT_LOW_MEM else MAX_CONCURRENT_LOW_MEM + 1
        if (activeHeavySessions >= limit) {
            return "$activeHeavySessions heavy sessions already running. Starting another risks the OS killing them all."
        }
        return null
    }

    fun logSnapshot(tag: String = TAG, extra: String = "") {
        val (total, available) = memInfoKb()
        Log.d(tag, "meminfo total=${total / 1024}MB available=${available / 1024}MB $extra")
    }

    private fun String.kbValue(): Long =
        substringAfter(':').trim().takeWhile { it.isDigit() }.toLongOrNull() ?: -1L
}

/** One live session as persisted on disk, so it can be re-attached later. */
data class SessionRecord(
    val id: String,
    val kind: String,
    val title: String,
    val projectRoot: String,
    val argv: List<String>,
    val environment: Map<String, String>,
    val logPath: String,
    val fifoPath: String,
    val pid: Int,
    val startedAt: Long,
    val state: String,
) {
    val isRunning: Boolean get() = state == STATE_RUNNING

    companion object {
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"
        const val STATE_COMPLETED = "completed"
    }
}

/**
 * On-disk registry of detached sessions.
 *
 * Sessions outlive the app process, so the app has to be able to find them
 * again after a cold start: the registry is the single source of truth for
 * "which agents/shells are alive, where their logs are, how to talk to them".
 */
class DetachedSessionRegistry(private val dir: File) {

    private val file: File get() = File(dir, "sessions.json")

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun list(): List<SessionRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toRecord()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(record: SessionRecord) {
        val records = list().filterNot { it.id == record.id } + record
        write(records)
    }

    @Synchronized
    fun update(id: String, state: String) {
        val records = list().map { if (it.id == id) it.copy(state = state) else it }
        write(records)
    }

    @Synchronized
    fun remove(id: String) {
        write(list().filterNot { it.id == id })
    }

    /** Drops records whose process is no longer alive. */
    @Synchronized
    fun pruneDead(): List<SessionRecord> {
        val alive = mutableListOf<SessionRecord>()
        val dead = mutableListOf<SessionRecord>()
        list().forEach { record ->
            val running = DetachedProcess.adopt(record.pid, File(record.logPath), File(record.fifoPath)).isAlive
            (if (running) alive else dead).add(record)
        }
        if (dead.isNotEmpty()) {
            write(alive.map { if (it.state == SessionRecord.STATE_RUNNING) it.copy(state = SessionRecord.STATE_STOPPED) else it })
        }
        return dead
    }

    private fun write(records: List<SessionRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        runCatching { file.writeText(array.toString()) }
            .onFailure { Log.w("ForgeSessions", "registry write failed: ${it.message}") }
    }

    private fun SessionRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("title", title)
        put("projectRoot", projectRoot)
        put("argv", JSONArray(argv))
        put("environment", JSONObject(environment.toMap()))
        put("logPath", logPath)
        put("fifoPath", fifoPath)
        put("pid", pid)
        put("startedAt", startedAt)
        put("state", state)
    }

    private fun JSONObject.toRecord(): SessionRecord? = runCatching {
        val argvArray = optJSONArray("argv") ?: JSONArray()
        val envObject = optJSONObject("environment") ?: JSONObject()
        SessionRecord(
            id = getString("id"),
            kind = optString("kind", "agent"),
            title = optString("title", "session"),
            projectRoot = optString("projectRoot", ""),
            argv = (0 until argvArray.length()).map { argvArray.getString(it) },
            environment = envObject.keys().asSequence().associateWith { envObject.getString(it) },
            logPath = getString("logPath"),
            fifoPath = getString("fifoPath"),
            pid = optInt("pid", -1),
            startedAt = optLong("startedAt", 0L),
            state = optString("state", SessionRecord.STATE_STOPPED),
        )
    }.getOrNull()
}
