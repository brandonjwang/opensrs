package com.opensrs.sync

import com.opensrs.data.local.DialectMode
import com.opensrs.data.local.RomanizationPref
import com.opensrs.data.local.UserSettings
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
 * formatVersion 2 adds the `prefs` object so a restore also restores user settings:
 * {
 *   "formatVersion": 2,
 *   "exportedAt": <epoch ms>,
 *   "deviceName": "...",
 *   "cards": [ {"w","s","e","i","r","d","u","t","l"} ],
 *   "prefs": {"dailyNew":10,"dailyReviews":100,"hskMax":3,"hskMin":0,
 *             "dialect":"DUAL","roman":"PINYIN","autoTts":true,"englishFirst":false}
 * }
 *
 * v1 payloads (no prefs) decode fine; prefs simply come back null.
 */
object BackupCodec {

    const val FORMAT_VERSION = 2

    fun encode(
        cards: List<FlashcardStateEntity>,
        deviceName: String,
        exportedAt: Long,
        prefs: UserSettings? = null,
    ): ByteArray {
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

        prefs?.let {
            root.put(
                "prefs",
                JSONObject()
                    .put("dailyNew", it.dailyNewLimit)
                    .put("dailyReviews", it.dailyReviewLimit)
                    .put("hskMax", it.hskMaxLevel)
                    .put("hskMin", it.hskMinLevel)
                    .put("dialect", it.dialectMode.name)
                    .put("roman", it.romanization.name)
                    .put("autoTts", it.autoPlayTts)
                    .put("englishFirst", it.showEnglishFirst),
            )
        }

        return gzip(root.toString().toByteArray(Charsets.UTF_8))
    }

    /** Returns parsed cards, payload timestamp, and optional preferences. */
    fun decode(bytes: ByteArray): DecodedBackup {
        val json = String(gunzip(bytes), Charsets.UTF_8)
        val root = JSONObject(json)
        val version = root.optInt("formatVersion", -1)
        require(version in 1..FORMAT_VERSION) { "Unsupported backup formatVersion: $version" }
        val arr = root.getJSONArray("cards")
        val cards = ArrayList<FlashcardStateEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            cards.add(
                FlashcardStateEntity(
                    wordId = o.getLong("w"),
                    // Unknown state string must FAIL the decode, not fall back to
                    // NEW: a fallback resurrects suspended words and, via LWW,
                    // propagates the resurrection to every device. The engine
                    // aborts the sync when decode fails — the safe direction.
                    state = CardState.valueOf(o.getString("s")),
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
        var prefs: UserSettings? = null
        if (root.has("prefs")) {
            runCatching {
                val p = root.getJSONObject("prefs")
                prefs = UserSettings(
                    dailyNewLimit = p.optInt("dailyNew", 10),
                    dailyReviewLimit = p.optInt("dailyReviews", 100),
                    hskMaxLevel = p.optInt("hskMax", 3),
                    hskMinLevel = p.optInt("hskMin", 0),
                    dialectMode = enumOr(p.optString("dialect"), DialectMode.DUAL),
                    romanization = enumOr(p.optString("roman"), RomanizationPref.PINYIN),
                    autoPlayTts = p.optBoolean("autoTts", true),
                    showEnglishFirst = p.optBoolean("englishFirst", false),
                )
            }
        }
        return DecodedBackup(
            cards = cards,
            exportedAt = root.getLong("exportedAt"),
            formatVersion = version,
            prefs = prefs,
        )
    }

    data class DecodedBackup(
        val cards: List<FlashcardStateEntity>,
        val exportedAt: Long,
        val formatVersion: Int,
        val prefs: UserSettings?,
    )

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E =
        raw?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
