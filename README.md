# Open SRS

SRS (spaced repetition) for Chinese — learn 普通话 and 粵語 words the Anki way:
cards come back right before you'd forget them.

**Open SRS** is a free Android flashcard app for intermediate learners. Pick an
HSK band, review a few cards a day, and your progress backs itself up to your
own Google Drive — no account with us, no server, no ads. Words are ordered by
how often people actually say them, and every card speaks aloud in Mandarin,
Cantonese, or both.

<details>
<summary>Highlights</summary>

- Offline SM-2 spaced repetition (Again / Hard / Good / Easy)
- 10,970-word spoken-frequency dictionary ordered by SUBTLEX-CH / HKCanCor ranks
- HSK 3.0 bands: study everything, or skip straight to band 4+
- Skip words you already know, permanently
- Dual-dialect text-to-speech (zh-CN + zh-HK), auto-play on reveal
- Automatic backup to a hidden folder in your own Google Drive;
  uninstall/reinstall restores everything on first sync
</details>

## Download

Grab the latest APK from [Releases](../../releases) and sideload it
("install unknown apps" permission). These are **alpha** builds: features move,
the database may migrate without ceremony. Versioning: every release tag
`vX.Y.Z-alpha.N` becomes `versionName X.Y.Z-alpha.N`; the in-app version code is
the commit count, so newer releases always upgrade over older ones.

## Why not just use Anki?

Anki is great — this app exists because getting it to *do Chinese well* takes
hours of deck-hunting and add-on plumbing. Open SRS is that work, done:

| | Anki | Open SRS |
|---|---|---|
| **First review session** | Download the app, find a shared deck (quality varies), import, configure | Install → tap → reviewing frequency-ordered words in ~30 seconds |
| **Word order** | Whatever the deck author chose | Every word ranked by how often it's actually *spoken* (SUBTLEX-CH film-subtitle corpus for Mandarin, HKCanCor for Cantonese) — you learn 你好 before 酗酒 without anyone curating it |
| **Mandarin + Cantonese** | Two separate decks, two pronunciation setups, Cantonese audio is hard to source | One queue; every card speaks in 普通话， 粵語， or both — built in, no add-ons |
| **Audio** | Configure TTS add-ons or hunt down sound files | Native system TTS with auto-play on reveal |
| **"I know this word already"** | Bury, suspend, or delete — per card, by hand | One "I already know this word" button, applied forever, synced everywhere |
| **HSK scoping** | Depends entirely on which deck you downloaded | Built-in: study all bands or skip straight to band 4+ when bands 1–3 are beneath you |
| **Backup & devices** | AnkiWeb account (or self-hosting a sync server) | Your own Google Drive, invisibly. Uninstall, flash a new ROM, reinstall — sign in and everything comes back |
| **Accounts & data** | Sync account | None. No server exists; your progress lives on your phone and your Drive |

Honest footnote: Anki does more overall — FSRS scheduling, desktop/web clients,
a giant ecosystem, any subject you like. If you're already deep into Anki with
a deck you love, keep it. Open SRS is for people who just want to learn
*Chinese*, spoken, without setting up a study system first.

## Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.0, JVM 17 |
| UI | Jetpack Compose + Material 3 |
| Dictionary DB | Pre-built SQLite asset (`words.db`), read-only via Room `createFromAsset` |
| User state DB | Room (`srs_state.db`), separate file so backups are exactly its contents |
| Prefs | Jetpack DataStore |
| Sync | Google Drive REST v3 `appDataFolder` over OkHttp; WorkManager periodic push/pull |
| Auth | Play Services `AuthorizationClient` (`drive.appdata` scope) |
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
  corpus/words.csv       Full dictionary source (see NOTICE for data licenses)
```

## Build

1. **Android Studio** Ladybug+ with SDK 35. Copy `local.properties` (or let Studio generate it).
2. Generate the dictionary asset:
   ```bash
   python3 tools/build_words_db.py
   ```
3. `./gradlew :app:assembleDebug` or run from Android Studio.
4. Unit tests (pure JVM, no emulator):
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

### Corpus & data licenses

`tools/corpus/words.csv` is the full dictionary source: 10,970 entries with
HSK 3.0 band assignments, spoken-frequency ranks, and romanized example
sentences. Provenance:

- HSK wordlists: [drkameleon/complete-hsk-vocabulary](https://github.com/drkameleon/complete-hsk-vocabulary) (MIT)
- Example sentences: [Tatoeba](https://tatoeba.org) via OPUS (CC-BY 2.0 FR)
- Mandarin ranks: SUBTLEX-CH (Cai & Brysbaert 2010, PLoS ONE)
- Cantonese ranks: HKCanCor
- Jyutping: PyCantonese (LSHK scheme) + LSHK lookup fallback

Full attributions and terms live in [NOTICE](NOTICE).

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
5. Done — `DriveAuthManager` mints access tokens via Play Services
   `AuthorizationClient` with that client; no client secret or ID string is
   embedded in the app.

> **Scope note:** `drive.appdata` is a Google *restricted* OAuth scope. While the
> Cloud project is in **Testing** mode, only accounts you add as test users can
> sync (≤100), and they see an unverified-app warning — this falls under Google's
> personal-use/testing exceptions. A public Play Store release requires completing
> [OAuth app verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification)
> (brand verification, public home page + privacy policy, demo video). No CASA
> security assessment should be needed: there is no server; data stays on-device
> and in the user's own hidden Drive folder.

> Auth stack note: Credential Manager's ID-token flow cannot mint Drive-scoped
> access tokens; the Play Services Authorization API (`AuthorizationClient.authorize`)
> is the supported zero-backend path and is what this project uses.

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

## License

Code: [Apache-2.0](LICENSE). Dictionary data: see [NOTICE](NOTICE) for the
per-source terms (MIT / CC-BY 2.0 FR / academic corpora, attribution given).
