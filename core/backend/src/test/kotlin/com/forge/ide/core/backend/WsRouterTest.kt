package com.forge.ide.core.backend

import com.forge.ide.core.backendapi.Envelope
import com.forge.ide.core.backendapi.Methods
import com.forge.ide.core.backendapi.Request
import com.forge.ide.core.backendapi.Response
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Host tests for the protocol router — no sockets, no Android. These run in CI
 * on every push and pin the contract between the UI process and the backend.
 */
class WsRouterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var scope: CoroutineScope
    private lateinit var services: BackendServices
    private val responses = mutableListOf<Envelope>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        workspace = tempFolder.newFolder("workspace")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        services = BackendServices.createForTest(workspace, scope)
    }

    @After
    fun tearDown() {
        services.shutdown()
    }

    private suspend fun call(id: Long, method: String, params: JsonObject = JsonObject(emptyMap())): Response {
        responses.clear()
        services.router.handle({ envelope -> responses += envelope }, request(id, method, params))
        return responses.filterIsInstance<Response>().single()
    }

    private fun request(id: Long, method: String, params: JsonObject) =
        json.encodeToString(Request.serializer(), Request(id, method, params))

    @Test
    fun `health responds ok`() = runBlocking {
        val response = call(1, Methods.HEALTH)
        assertTrue(response.ok)
        assertTrue(response.result.toString().contains("ok"))
    }

    @Test
    fun `write then read round-trips content`() = runBlocking {
        val file = File(workspace, "notes/hello.txt").path
        val write = call(
            2,
            Methods.FS_WRITE,
            buildJsonObject { put("path", file); put("content", "hello forge") },
        )
        assertTrue(write.ok)

        val read = call(3, Methods.FS_READ, buildJsonObject { put("path", file) })
        val result = json.decodeFromJsonElement(
            com.forge.ide.core.backendapi.FsReadResult.serializer(),
            read.result!!,
        )
        assertEquals("hello forge", result.content)
        assertFalse(result.truncated)
        assertEquals(11L, result.totalSize)
    }

    @Test
    fun `list returns sorted directories first`() = runBlocking {
        File(workspace, "zeta.txt").writeText("z")
        File(workspace, "alpha").mkdirs()

        val response = call(4, Methods.FS_LIST, buildJsonObject { put("path", workspace.path) })
        val result = json.decodeFromJsonElement(
            com.forge.ide.core.backendapi.FsListResult.serializer(),
            response.result!!,
        )
        assertEquals(listOf("alpha", "zeta.txt"), result.entries.map { it.name })
        assertTrue(result.entries.first().isDirectory)
    }

    @Test
    fun `path traversal outside workspace is rejected`() = runBlocking {
        val escape = File(workspace, "../escaped.txt").path
        val response = call(5, Methods.FS_READ, buildJsonObject { put("path", escape) })
        assertFalse(response.ok)
        assertEquals("outside_workspace", response.error?.code)
    }

    @Test
    fun `mkdir rename and delete move through trash`() = runBlocking {
        val dir = File(workspace, "dir").path
        assertTrue(call(6, Methods.FS_MKDIR, buildJsonObject { put("path", dir) }).ok)

        val file = "$dir/a.txt"
        assertTrue(
            call(
                7,
                Methods.FS_WRITE,
                buildJsonObject { put("path", file); put("content", "x") },
            ).ok,
        )

        val renamed = File(workspace, "dir/b.txt").path
        assertTrue(
            call(
                8,
                Methods.FS_RENAME,
                buildJsonObject { put("from", file); put("to", renamed) },
            ).ok,
        )
        assertTrue(File(renamed).exists())

        val delete = call(
            9,
            Methods.FS_DELETE,
            buildJsonObject { put("path", File(workspace, "dir").path) },
        )
        assertTrue(delete.ok)
        assertTrue(File(workspace, FileService.TRASH_DIR).exists())
    }

    @Test
    fun `unknown method returns not_implemented`() = runBlocking {
        val response = call(10, "nope.nothing")
        assertFalse(response.ok)
        assertEquals("not_implemented", response.error?.code)
    }

    @Test
    fun `build methods are recognised but deferred`() = runBlocking {
        val response = call(11, Methods.BUILD_START)
        assertFalse(response.ok)
        assertEquals("not_implemented", response.error?.code)
        assertTrue(response.error?.message?.contains("M1b") == true)
    }
}
