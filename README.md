# Open SRS — Intermediate Chinese SRS (Mandarin + Cantonese)

Production-grade Android app for intermediate Chinese learners: spoken-frequency
dictionary (SUBTLEX-CH / HKCAC-utd), offline SM-2 spaced repetition, native
dual-dialect TTS, and zero-backend Google Drive backup.

## Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.0, JVM 17 |
| UI | Jetpack Compose + Material 3 |
| Dictionary DB | Pre-built SQLite asset (`words.db`), read-only via Room `createFromAsset` |
| User state DB | Room (`srs_state.db`), separate file so backups are exactly its contents |
| Prefs | Jetpack DataStore |
| Sync | Google Drive REST v3 `appDataFolder` over OkHttp; WorkManager periodic push/pull |
| Auth | Google Play Services `GoogleSignIn` + `GoogleAuthUtil` (drive.appdata scope) |
| Audio | Native `android.speech.tts.TextToSpeech`, zh-CN + zh-HK engines |
| Min SDK | 29 |

## Project layout

```
app/src/main/java/com/opensrs/
  OpenSrsApp.kt          App + manual DI container + WorkManager factory
  MainActivity.kt        Compose NavHost (review ↔ settings)
  data/db/               WordEntity/Dao, FlashcardStateEntity/Dao, both RoomDatabases
  data/local/            DataStore preferences repository
  data/repo/             StudyRepository: queue assembly across the two DB files
  srs/SrsScheduler.kt    Pure SM-2 engine (Again/Hard/Good/Easy)
  audio/TtsManager.kt    Dual-engine TTS wrapper (MANDARIN | CANTONESE | DUAL)
  sync/
    DriveAuthManager.kt  Consent flow + silent access tokens
    DriveService.kt      appDataFolder REST calls
    BackupCodec.kt       srs_state_backup.json.gz encode/decode (formatVersion=1)
    DriveSyncEngine.kt   LWW merge + push superset
    SyncWorker.kt        6-hour periodic worker (unmetered only)
  ui/review/             ReviewScreen + ReviewViewModel
  ui/settings/           SettingsScreen + SettingsViewModel
tools/
  build_words_db.py      Corpus CSV -> validated words.db generator
  corpus/words.csv       Demo corpus (replace with real SUBTLEX-CH/HKCAC exports)
```

## Build

1. **Android Studio** Ladybug+ with SDK 34. Copy `local.properties` (or let Studio generate it).
2. Generate the dictionary asset:
   ```bash
   python3 tools/build_words_db.py
   ```
3. `./gradlew :app:assembleDebug` or run from Android Studio.
4. Unit tests (pure JVM, no emulator):
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

### Corpus

Replace `tools/corpus/words.csv` with full corpus exports. Columns:

`simplified,traditional,pinyin,jyutping,english,mandarin_rank,cantonese_rank,examples`

- Ranks: integers from SUBTLEX-CH (spoken subtitle frequencies) and HKCAC /
  utd-cantonese. Leave blank when absent from a corpus — the queue query then
  deprioritizes but still includes the word.
- `examples`: JSON array of `{"zh","py","jp","en"}` objects.
- The generator assigns ids in ascending effective-spoken-rank order, making
  `ORDER BY id` a deterministic frequency tiebreak for every DAO query.

## Enabling Google Drive sync

One-time console setup, no server required:

1. [Google Cloud Console](https://console.cloud.google.com) → create/select project.
2. **APIs & Services → Library** → enable **Google Drive API**.
3. **OAuth consent screen**: External, add scope
   `https://www.googleapis.com/auth/drive.appdata`; add your account as test user
   while unverified.
4. **Credentials → Create OAuth client ID → Android**, package name
   `com.opensrs`, SHA-1 from:
   ```bash
   keytool -keystore ~/.android/debug.keystore -list -v -alias androiddebugkey -storepass android
   ```
5. Done — `DriveAuthManager` uses `GoogleAuthUtil` with that client; no client
   secret or ID string is embedded in the app.

> Note on auth stack: Credential Manager's ID-token flow cannot mint Drive-scoped
> access tokens; `GoogleAuthUtil.getToken` with the `oauth2:` scope prefix is the
> supported zero-backend path and is what this project uses.

## Sync payload & conflict resolution

- File: `srs_state_backup.json.gz` (gzip'd JSON, `formatVersion: 1`).
- Contents: per-card `{w,s,e,i,r,d,u,t,l}` = wordId, state, easeFactor,
  intervalDays, repetitions, dueAt, updatedAt, totalReviews, lapses. The static
  dictionary is never uploaded.
- Merge: per-card Last-Write-Wins on `updatedAt`; the post-merge superset is
  pushed back so concurrent devices converge. Whole-file freshness additionally
  guarded by payload `exportedAt`.
- Manual: Settings → "Sync now". Automatic: WorkManager every 6 h on unmetered.

## Extending

- **FSRS**: implement the same `SrsScheduler.review(card, rating, now)` signature;
  tests in `SrsSchedulerTest` document the contract.
- **Schema changes**: bump `SrsStateDatabase` version + add migration AND bump
  `BackupCodec.FORMAT_VERSION` with a decode path for old payloads.
