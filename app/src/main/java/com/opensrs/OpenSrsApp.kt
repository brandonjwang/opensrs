package com.opensrs

import android.app.Application
import androidx.work.Configuration
import com.opensrs.audio.TtsManager
import com.opensrs.data.db.SrsStateDatabase
import com.opensrs.data.db.WordsDatabase
import com.opensrs.data.local.PreferencesRepository
import com.opensrs.data.repo.StudyRepository
import com.opensrs.srs.SrsScheduler
import com.opensrs.sync.DriveSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. Deliberately not Hilt: the dependency graph is small,
 * fully eager-failable at startup, and one file keeps it auditable.
 */
class AppContainer(app: OpenSrsApp) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val wordsDb: WordsDatabase by lazy { WordsDatabase.build(app) }
    val srsDb: SrsStateDatabase by lazy { SrsStateDatabase.build(app) }

    val wordDao get() = wordsDb.wordDao()
    val cardDao get() = srsDb.cardDao()

    val preferences: PreferencesRepository by lazy { PreferencesRepository(app) }

    val scheduler: SrsScheduler = SrsScheduler()

    val repository: StudyRepository by lazy {
        StudyRepository(wordDao, cardDao, scheduler)
    }

    val statsRepository: com.opensrs.data.repo.StatsRepository by lazy {
        com.opensrs.data.repo.StatsRepository(wordDao, cardDao)
    }

    val tts: TtsManager by lazy { TtsManager(app) }

    /** In-memory normalized search index; built once, off the main thread. */
    val searchIndex: kotlinx.coroutines.Deferred<com.opensrs.data.db.WordSearchIndex> by lazy {
        appScope.async { com.opensrs.data.db.WordSearchIndex.build(wordDao) }
    }

    val syncEngine: DriveSyncEngine by lazy {
        DriveSyncEngine(
            context = app,
            preferences = preferences,
            srsDb = srsDb,
            externalScope = appScope,
        )
    }
}

class OpenSrsApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.syncEngine.start()
        com.opensrs.sync.SyncWorker.schedule(this)
    }

    /**
     * Custom WorkerFactory so [com.opensrs.sync.SyncWorker] receives the real
     * container instead of being reflectively instantiated empty.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SyncWorkerFactory(this))
            .build()
}
