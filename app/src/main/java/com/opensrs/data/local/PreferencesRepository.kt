package com.opensrs.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "opensrs_prefs")

enum class DialectMode { MANDARIN, CANTONESE, DUAL }

/** Which romanization line is shown expanded by default on cards. */
enum class RomanizationPref { PINYIN, JYUTPING }

data class UserSettings(
    val dailyNewLimit: Int,
    val dailyReviewLimit: Int,
    /** Maximum HSK 3.0 band in the study pool; 0 = all bands. */
    val hskMaxLevel: Int,
    /** Lowest HSK 3.0 band in the study pool; 0 = start from band 1. */
    val hskMinLevel: Int,
    val dialectMode: DialectMode,
    val romanization: RomanizationPref,
    val autoPlayTts: Boolean,
    val showEnglishFirst: Boolean,
)

/** Default until changed; used for new-card frequency ordering and TTS order. */
val UserSettings.prefersCantonese: Boolean get() = dialectMode != DialectMode.MANDARIN

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val DAILY_NEW = intPreferencesKey("daily_new_limit")
        val DAILY_REVIEWS = intPreferencesKey("daily_review_limit")
        val HSK_MAX_LEVEL = intPreferencesKey("hsk_max_level")
        val HSK_MIN_LEVEL = intPreferencesKey("hsk_min_level")
        val DIALECT = stringPreferencesKey("dialect_mode")
        val ROMANIZATION = stringPreferencesKey("romanization_pref")
        val AUTO_TTS = booleanPreferencesKey("auto_play_tts")
        val ENGLISH_FIRST = booleanPreferencesKey("show_english_first")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val ACCOUNT = stringPreferencesKey("drive_account")
        val BACKUP_UPDATED = longPreferencesKey("drive_backup_updated_at")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            dailyNewLimit = p[Keys.DAILY_NEW] ?: 10,
            dailyReviewLimit = p[Keys.DAILY_REVIEWS] ?: 100,
            hskMaxLevel = p[Keys.HSK_MAX_LEVEL] ?: 3,
            dialectMode = enumOrDefault(p[Keys.DIALECT], DialectMode.DUAL),
            romanization = enumOrDefault(p[Keys.ROMANIZATION], RomanizationPref.PINYIN),
            hskMinLevel = p[Keys.HSK_MIN_LEVEL] ?: 0,
            autoPlayTts = p[Keys.AUTO_TTS] ?: true,
            showEnglishFirst = p[Keys.ENGLISH_FIRST] ?: false,
        )
    }

    /** Snapshot read for workers/sync; avoids collecting flows off the UI thread. */
    suspend fun settingsSnapshot(): UserSettings = settings.first()

    val lastSyncAt: Flow<Long?> = context.dataStore.data.map { it[Keys.LAST_SYNC_AT] }
    val driveAccount: Flow<String?> = context.dataStore.data.map { it[Keys.ACCOUNT] }
    val backupUpdatedAt: Flow<Long?> = context.dataStore.data.map { it[Keys.BACKUP_UPDATED] }

    suspend fun setDailyNewLimit(v: Int) = setInt(Keys.DAILY_NEW, v.coerceIn(0, 200))
    suspend fun setDailyReviewLimit(v: Int) = setInt(Keys.DAILY_REVIEWS, v.coerceIn(10, 9999))
    suspend fun setHskMaxLevel(v: Int) = setInt(Keys.HSK_MAX_LEVEL, v.coerceIn(0, 7))
    suspend fun setDialectMode(v: DialectMode) = setString(Keys.DIALECT, v.name)
    suspend fun setRomanization(v: RomanizationPref) = setString(Keys.ROMANIZATION, v.name)
    suspend fun setHskMinLevel(v: Int) = setInt(Keys.HSK_MIN_LEVEL, v.coerceIn(0, 7))
    suspend fun setAutoPlayTts(v: Boolean) = setBool(Keys.AUTO_TTS, v)
    suspend fun setShowEnglishFirst(v: Boolean) = setBool(Keys.ENGLISH_FIRST, v)
    suspend fun setSyncMetadata(account: String?, lastSyncAt: Long?, backupUpdatedAt: Long?) {
        context.dataStore.edit { p ->
            account?.let { p[Keys.ACCOUNT] = it }
            lastSyncAt?.let { p[Keys.LAST_SYNC_AT] = it }
            backupUpdatedAt?.let { p[Keys.BACKUP_UPDATED] = it }
        }
    }

    suspend fun clearAccount() {
        context.dataStore.edit { p ->
            p.remove(Keys.ACCOUNT)
            p.remove(Keys.LAST_SYNC_AT)
            p.remove(Keys.BACKUP_UPDATED)
        }
    }

    private suspend fun setInt(k: androidx.datastore.preferences.core.Preferences.Key<Int>, v: Int) {
        context.dataStore.edit { it[k] = v }
    }

    private suspend fun setBool(k: androidx.datastore.preferences.core.Preferences.Key<Boolean>, v: Boolean) {
        context.dataStore.edit { it[k] = v }
    }

    private suspend fun setString(k: androidx.datastore.preferences.core.Preferences.Key<String>, v: String) {
        context.dataStore.edit { it[k] = v }
    }
}

private inline fun <reified E : Enum<E>> enumOrDefault(raw: String?, default: E): E =
    raw?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
