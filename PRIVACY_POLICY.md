# Open SRS Privacy Policy

Last updated: 2026-08-26

Open SRS is an offline flash-card app for Chinese study. This policy describes
what data the app handles and where it lives.

## Data the developer collects

**None.** The app contains no analytics, no advertising, no crash reporting,
and no telemetry of any kind. The developer operates no server and never
receives your data.

## Data stored on your device

- **Study progress** (card scheduling state) is stored in a local database in
  the app's private storage on your device.
- **Settings** (daily limits, HSK band range, dialect and display preferences)
  are stored locally via Android DataStore.

## Google Drive backup

If you opt in by signing in, the app stores a single backup file
(`srs_state_backup.json.gz`) containing only your study progress and settings
in a **hidden, app-private folder (`appDataFolder`)** inside **your own**
Google Drive. Consequences:

- Only this app can read that folder — not other apps, and not people who have
  access to your Drive file list.
- The developer has no access to it. There is no intermediary server; your
  device talks directly to Google's Drive API with a token minted on-device.
- You can revoke access at any time via Google Account → Security →
  Third-party apps with account access, or by signing out in the app.
- Deleting the backup: sign out removes app access; you can also delete the
  hidden "Open SRS" data folder from your Drive storage settings.
- Google's handling of your Google account data is governed by
  [Google's Privacy Policy](https://policies.google.com/privacy).

## Dictionary content

The bundled dictionary is read-only static data bundled in the APK; it contains
no personal information. Sources and licenses are listed in the project's
[NOTICE](NOTICE) file.

## Children

The app is not directed at children under 13 and collects no data from anyone,
including children.

## Changes

Material changes to this policy will be reflected in the app's repository and
Play Store listing before distribution.

## Contact

Brandon Wang — https://github.com/brandonjwang/opensrs
