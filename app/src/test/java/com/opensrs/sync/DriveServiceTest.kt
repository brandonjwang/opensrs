package com.opensrs.sync

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Regression tests for the sync outage: Drive REST v3 returns COMPACT JSON
 * (`"id":"x"`, no space after the colon). The old string-prefix parser only
 * matched pretty-printed JSON, so `findOrCreate` never found the existing file,
 * minted an empty id, and every upload PATCHed `/files/` -> HTTP 404.
 */
class DriveServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: DriveService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = DriveService(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `finds existing file in compact json`() {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"drive#fileList","incompleteSearch":false,"files":[""" +
                    """{"kind":"drive#file","id":"abc123","name":"srs_state_backup.json.gz"}]}""",
            ),
        )
        assertEquals("abc123", service.findOrCreate("tok", DriveService.BACKUP_FILE_NAME))
    }

    @Test
    fun `creates file and parses compact id response`() {
        server.enqueue(MockResponse().setBody("""{"kind":"drive#fileList","files":[]}"""))
        server.enqueue(
            MockResponse().setBody("""{"kind":"drive#file","id":"newId9","name":"srs_state_backup.json.gz"}"""),
        )
        assertEquals("newId9", service.findOrCreate("tok", DriveService.BACKUP_FILE_NAME))
        val req = server.takeRequest()
        assertEquals("/drive/v3/files", req.path?.substringBefore('?'))
        val create = server.takeRequest()
        assertEquals("/upload/drive/v3/files", create.path?.substringBefore('?'))
        assertTrue(create.body.readUtf8().contains("appDataFolder"))
    }

    @Test
    fun `empty file list returns fresh create path`() {
        server.enqueue(MockResponse().setBody("""{"kind":"drive#fileList","files":[]}"""))
        server.enqueue(MockResponse().setBody("""{"kind":"drive#file","id":"created1"}"""))
        assertEquals("created1", service.findOrCreate("tok", DriveService.BACKUP_FILE_NAME))
        assertEquals("/drive/v3/files", server.takeRequest().path?.substringBefore('?'))
        assertEquals("/upload/drive/v3/files", server.takeRequest().path?.substringBefore('?'))
    }

    @Test
    fun `download returns payload bytes`() {
        val payload = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 1, 2, 3)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        assertArrayEquals(payload, service.download("tok", "f1"))
    }

    @Test
    fun `http error surfaces as IOException with status`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        try {
            service.download("tok", "gone")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
    }
}
