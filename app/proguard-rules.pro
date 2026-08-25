# Kotlin serialization-free JSON is hand-rolled with org.json (Android platform),
# so most rules below guard against reflection-based stripping that we don't use.
# Keep them minimal but explicit.

# OkHttp / Conscrypt / BouncyCastle TLS plumbing
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# GMS Identity/Authorization API is reflection-free but obfuscated builds need
# the API surface kept for Play Services binding.
-keep class com.google.android.gms.auth.api.identity.** { *; }

# WorkManager workers are instantiated reflectively by name.
-keep class com.opensrs.sync.SyncWorker { *; }
