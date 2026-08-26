package com.opensrs.sync

import android.content.Context
import com.opensrs.data.db.FlashcardStateEntity
import com.opensrs.data.db.SrsStateDatabase
import com.opensrs.data.local.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Zero-backend sync over Google Drive appDataFolder.
 *
 * Payload: [BackupCodec]-encoded `srs_state_backup.json.gz` holding ONLY user SRS
 * state (cards table) — never the static dictionary.
 *
 * Conflict resolution: per-card Last-Write-Wins on `updatedAt`, merged into Room
 * inside one transaction; the post-merge superset is then pushed so every device
 * converges.
 */
class DriveSyncEngine(
    private val context: Context,
    private val preferences: PreferencesRepository,
    private val srsDb: SrsStateDatabase,
    private val externalScope: CoroutineScope,
) {

    sealed class SyncResult {
        data object Pushed : SyncResult()
        data object Pulled : SyncResult()
        data object Merged : SyncResult()
        data object InSync : SyncResult()
        data class Failed(val reason: String) : SyncResult()
    }

    data class SyncStatus(
        val running: Boolean = false,
        val lastMessage: String = "Never synced",
        val lastSyncAt: Long? = null,
    )

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    /**
     * Non-null when the last sync aborted because Google needs interactive
     * Drive-scope consent. The UI must start this IntentSender via
     * [androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult];
     * after the user approves, retrying sync succeeds silently.
     */
    private val _consentRequired = MutableStateFlow<android.content.IntentSender?>(null)
    val consentRequired: StateFlow<android.content.IntentSender?> = _consentRequired

    /** User dismissed the approval dialog; stop re-surfacing it until next attempt. */
    fun dismissConsent() {
        _consentRequired.value = null
    }

    /** Serializes syncs; a WorkManager run and a manual pull never interleave. */
    private val syncMutex = Mutex()

    private val service by lazy { DriveService() }
    private val auth by lazy { DriveAuthManager(context, preferences) }

    /** Internal so tests can substitute a fake token source. */
    internal var tokenProvider: suspend () -> String = {
        val email = preferences.driveAccount.first()
            ?: throw IllegalStateException("Not signed in")
        auth.accessToken(DriveAuthManager.accountFor(email))
    }

    fun start() {
        externalScope.launch {
            preferences.lastSyncAt.collect { last ->
                _status.value = _status.value.copy(lastSyncAt = last)
            }
        }
    }

    /** Full bidirectional sync; see class docs. */
    suspend fun syncNow(): SyncResult = syncMutex.withLock {
        _status.value = _status.value.copy(running = true, lastMessage = "Syncing…")
        val result = runCatching { doSync() }.getOrElse { e ->
            SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (result !is SyncResult.Failed) {
            _consentRequired.value = null // consent granted (or not needed anymore)
        }
        val message = when (result) {
            is SyncResult.Failed -> result.reason
            else -> "Sync complete"
        }
        _status.value = _status.value.copy(running = false, lastMessage = message)
        result
    }

    private suspend fun doSync(): SyncResult = withContext(Dispatchers.IO) {
        val token = try {
            tokenProvider()
        } catch (e: DriveAuthManager.ConsentRequired) {
            _consentRequired.value = e.pendingIntent.intentSender
            return@withContext SyncResult.Failed(CONSENT_MSG)
        } catch (e: IllegalStateException) {
            return@withContext SyncResult.Failed(e.message ?: "Not signed in")
        }

        val fileId = service.findOrCreate(token, DriveService.BACKUP_FILE_NAME)

        // -- Pull ---------------------------------------------------------------
        // A failed pull must abort the sync: pushing local-only state over a
        // healthy remote backup would silently destroy the other device's data.
        val remoteBytes = try {
            service.download(token, fileId)
        } catch (e: IOException) {
            return@withContext SyncResult.Failed("Download failed: ${e.message}")
        }
        var remotePrefs: com.opensrs.data.local.UserSettings? = null
        val remoteCards = if (remoteBytes.size > GZIP_MIN_BYTES) {
            runCatching { BackupCodec.decode(remoteBytes) }
                .getOrElse { null }
                ?.also { remotePrefs = it.prefs }
                ?.cards
                ?: emptyList()
        } else {
            emptyList()
        }

        // -- Merge: per-card LWW on updatedAt ------------------------------------
        val dao = srsDb.cardDao()
        val localCards = dao.all()
        var pulledNewer = 0
        var keptLocal = 0
        val merged = LinkedHashMap<Long, FlashcardStateEntity>()
        localCards.forEach { merged[it.wordId] = it }

        for (remote in remoteCards) {
            val local = merged[remote.wordId]
            when {
                local == null -> {
                    merged[remote.wordId] = remote
                    pulledNewer++
                }
                remote.updatedAt > local.updatedAt -> {
                    merged[remote.wordId] = remote
                    pulledNewer++
                }
                else -> keptLocal++
            }
        }

        val mergedList = merged.values.toList()

        if (pulledNewer > 0) {
            dao.upsertAll(
                mergedList.filter { m ->
                    localCards.none { it.wordId == m.wordId && it.updatedAt >= m.updatedAt }
                },
            )
        }

        // -- Push post-merge superset ---------------------------------------------
        val localPrefs = preferences.settings.first()
        push(service, token, fileId, mergedList, localPrefs)
        recordSuccess()
        // Restore remote preferences only when this device has never synced before.
        if (remotePrefs != null && localCards.isEmpty() && pulledNewer > 0) {
            preferences.setDailyNewLimit(remotePrefs.dailyNewLimit)
            preferences.setDailyReviewLimit(remotePrefs.dailyReviewLimit)
            preferences.setHskMaxLevel(remotePrefs.hskMaxLevel)
            preferences.setDialectMode(remotePrefs.dialectMode)
            preferences.setRomanization(remotePrefs.romanization)
            preferences.setAutoPlayTts(remotePrefs.autoPlayTts)
            preferences.setHskMinLevel(remotePrefs.hskMinLevel)
            preferences.setShowEnglishFirst(remotePrefs.showEnglishFirst)
        }

        when {
            pulledNewer > 0 && keptLocal == 0 -> SyncResult.Pulled
            pulledNewer > 0 -> SyncResult.Merged
            remoteCards.isEmpty() && localCards.isNotEmpty() -> SyncResult.Pushed
            else -> SyncResult.InSync
        }
    }

    private suspend fun push(
        service: DriveService,
        token: String,
        fileId: String,
        cards: List<FlashcardStateEntity>,
        prefs: com.opensrs.data.local.UserSettings,
    ) = withContext(Dispatchers.IO) {
        val bytes = BackupCodec.encode(cards, deviceName(), System.currentTimeMillis(), prefs)
        service.upload(token, fileId, bytes)
    }

    private suspend fun recordSuccess() {
        preferences.setSyncMetadata(
            account = preferences.driveAccount.first() ?: return,
            lastSyncAt = System.currentTimeMillis(),
            backupUpdatedAt = System.currentTimeMillis(),
        )
    }

    private fun deviceName(): String = android.os.Build.MODEL ?: "android-device"

    companion object {
        /** Anything smaller cannot be a valid gzip payload (magic + footer). */
        const val GZIP_MIN_BYTES = 20

        /** Shown to the user when Drive-scope consent is needed. */
        const val CONSENT_MSG = "Approval needed — tap Sync now after granting"
    }
}
