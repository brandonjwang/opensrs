package com.opensrs.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Thin OkHttp client over the Google Drive REST v3 API restricted to the hidden
 * per-user `appDataFolder`. No backend server involved: the device talks to
 * googleapis.com directly with a user-consented OAuth2 token from
 * [DriveAuthManager].
 *
 * Endpoints used:
 *  - GET   /drive/v3/files?q=...&spaces=appDataFolder    (find backup file id)
 *  - POST  /upload/drive/v3/files?uploadType=multipart    (create)
 *  - PATCH /upload/drive/v3/files/{id}?uploadType=media   (update in place)
 *  - GET   /drive/v3/files/{id}?alt=media                 (download)
 */
class DriveService(
    private val http: OkHttpClient = OkHttpClient(),
    /** Overridable so tests can point at a MockWebServer. */
    private val baseUrl: String = BASE,
) {


    /**
     * Finds [fileName] in appDataFolder, creating an empty shell on first run.
     * Returns the Drive fileId.
     */
    fun findOrCreate(accessToken: String, fileName: String): String {
        findFileId(accessToken, fileName)?.let { return it }

        val meta = JSONObject()
            .put("name", fileName)
            .put("parents", JSONArray().put("appDataFolder"))
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "metadata",
                "metadata.json",
                meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .addFormDataPart(
                "file",
                fileName,
                ByteArray(0).toRequestBody("application/gzip".toMediaType()),
            )
            .build()

        val req = Request.Builder()
            .url("$baseUrl/upload/drive/v3/files?uploadType=multipart&fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        // Drive returns compact JSON (no spaces); string-prefix hacks never matched it.
        return JSONObject(execute(req)).getString("id")
    }

    /** Replaces the content of an existing appDataFolder file. */
    fun upload(accessToken: String, fileId: String, bytes: ByteArray) {
        val req = Request.Builder()
            .url("$baseUrl/upload/drive/v3/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $accessToken")
            .patch(bytes.toRequestBody("application/gzip".toMediaType()))
            .build()
        execute(req)
    }

    /** Downloads the current backup payload. */
    fun download(accessToken: String, fileId: String): ByteArray {
        val req = Request.Builder()
            .url("$baseUrl/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return executeBytes(req)
    }

    private fun findFileId(accessToken: String, name: String): String? {
        val q = java.net.URLEncoder.encode("name = '$name' and trashed = false", "UTF-8")
        val req = Request.Builder()
            .url("$baseUrl/drive/v3/files?q=$q&spaces=appDataFolder&fields=files(id,name)")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val files = JSONObject(execute(req)).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        return files.getJSONObject(0).getString("id")
    }

    private fun execute(req: Request): String = String(executeBytes(req))

    private fun executeBytes(req: Request): ByteArray {
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) throw IOException("Drive ${resp.code}: ${String(bytes)}")
            return bytes
        }
    }

    companion object {
        const val BASE = "https://www.googleapis.com"
        const val BACKUP_FILE_NAME = "srs_state_backup.json.gz"
    }
}
