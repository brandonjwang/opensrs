package com.openchinese.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Static spoken-frequency dictionary. Pre-populated at build time by
 * `tools/build_words_db.py` into `app/assets/words.db`, copied to internal
 * storage on first launch. Never written at runtime, never synced.
 */
@Database(entities = [WordEntity::class], version = 1)
abstract class WordsDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao

    companion object {
        private const val ASSET = "words.db"
        private const val NAME = "words.db"

        fun build(context: Context): WordsDatabase =
            Room.databaseBuilder(context, WordsDatabase::class.java, NAME)
                .createFromAsset(ASSET)
                .build()
    }
}

/**
 * Mutable per-user SRS progress. One file so the Drive sync payload is exactly
 * this database's contents. Schema changes require a Room migration plus a
 * backup-payload version bump in [com.openchinese.sync.BackupCodec].
 */
@Database(
    entities = [FlashcardStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SrsStateDatabase : RoomDatabase() {
    abstract fun cardDao(): FlashcardDao

    companion object {
        const val NAME = "srs_state.db"

        /**
         * The DB is created empty on first run. On restore we replace the file
         * atomically while no connection is open (see DriveSyncEngine), so the
         * builder must never hold a long-lived instance across a restore; the
         * container recreates it via [recreate].
         */
        fun build(context: Context): SrsStateDatabase =
            Room.databaseBuilder(context, SrsStateDatabase::class.java, NAME)
                // No destructive migration: user progress must never be silently wiped.
                .addMigrations(*ALL_MIGRATIONS)
                .build()

        /** All migrations; v1 is the first schema so this is empty for now. */
        val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf()
    }
}
