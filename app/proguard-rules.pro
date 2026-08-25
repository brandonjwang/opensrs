# Kotlin serialization-free JSON is hand-rolled with org.json (Android platform),
# so most rules below guard against reflection-based stripping that we don't use.
# Keep them minimal but explicit.

# OkHttp / Conscrypt / BouncyCastle TLS plumbing
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Credential Manager / Google ID
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# WorkManager workers are instantiated reflectively by name.
-keep class com.opensrs.sync.SyncWorker { *; }
