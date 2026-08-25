package com.opensrs.sync

import com.opensrs.data.db.CardState
import com.opensrs.data.db.FlashcardStateEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * `srs_state_backup.json.gz` codec.
 *
 * Wire format (version 1):
 * {
 *   "formatVersion": 1,
 *   "exportedAt": <epoch ms>,
 *   "deviceName": "...",
 *   "cards": [
 *     {"w":<wordId>,"s":"NEW|LEARNING|GRADUATED","e":2.5,"i":1.0,"r":0,
 *      "d":<epoch ms>,"u":<epoch ms>,"t":<totalReviews>,"l":<lapses>}
 *   ]
 * }
 *
 * Short keys: years of reviews stay small (~90 bytes/card gzipped). The static
 * dictionary is never part of this payload.
 */
object BackupCodec {

    const val FORMAT_VERSION = 1

    fun encode(cards: List<FlashcardStateEntity>, deviceName: String, exportedAt: Long): ByteArray {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("exportedAt", exportedAt)
        root.put("deviceName", deviceName)

        val arr = JSONArray()
        for (c in cards) {
            arr.put(
                JSONObject()
                    .put("w", c.wordId)
                    .put("s", c.state.name)
                    .put("e", c.easeFactor.toDouble())
                    .put("i", c.intervalDays)
                    .put("r", c.repetitions)
                    .put("d", c.dueAt)
                    .put("u", c.updatedAt)
                    .put("t", c.totalReviews)
                    .put("l", c.lapses),
            )
        }
        root.put("cards", arr)

        return gzip(root.toString().toByteArray(Charsets.UTF_8))
    }

    /** Returns parsed cards plus the payload timestamp used for Last-Write-Wins. */
    fun decode(bytes: ByteArray): DecodedBackup {
        val json = String(gunzip(bytes), Charsets.UTF_8)
        val root = JSONObject(json)
        val version = root.optInt("formatVersion", -1)
        require(version == FORMAT_VERSION) { "Unsupported backup formatVersion: $version" }
        val arr = root.getJSONArray("cards")
        val cards = ArrayList<FlashcardStateEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            cards.add(
                FlashcardStateEntity(
                    wordId = o.getLong("w"),
                    state = runCatching { CardState.valueOf(o.getString("s")) }
                        .getOrDefault(CardState.NEW),
                    easeFactor = o.getDouble("e").toFloat(),
                    intervalDays = o.getDouble("i").toFloat(),
                    repetitions = o.getInt("r"),
                    dueAt = o.getLong("d"),
                    updatedAt = o.getLong("u"),
                    totalReviews = o.getInt("t"),
                    lapses = o.getInt("l"),
                ),
            )
        }
        return DecodedBackup(
            cards = cards,
            exportedAt = root.getLong("exportedAt"),
            formatVersion = version,
        )
    }

    data class DecodedBackup(
        val cards: List<FlashcardStateEntity>,
        val exportedAt: Long,
        val formatVersion: Int,
    )

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
