package com.openchinese

import android.app.Application
import androidx.work.Configuration
import com.openchinese.audio.TtsManager
import com.openchinese.data.db.SrsStateDatabase
import com.openchinese.data.db.WordsDatabase
import com.openchinese.data.local.PreferencesRepository
import com.openchinese.data.repo.StudyRepository
import com.openchinese.srs.SrsScheduler
import com.openchinese.sync.DriveSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. Deliberately not Hilt: the dependency graph is small,
 * fully eager-failable at startup, and one file keeps it auditable.
 */
class AppContainer(app: OpenChineseApp) {
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

    val tts: TtsManager by lazy { TtsManager(app) }

    val syncEngine: DriveSyncEngine by lazy {
        DriveSyncEngine(
            context = app,
            preferences = preferences,
            srsDb = srsDb,
            appScope = appScope,
        )
    }
}

class OpenChineseApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Custom WorkerFactory so [com.openchinese.sync.SyncWorker] receives the real
     * container instead of being reflectively instantiated empty.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SyncWorkerFactory(this))
            .build()
}
