package com.opensrs.sync

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.opensrs.data.local.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google identity for the Drive `appDataFolder` scope.
 *
 * Account selection: classic [GoogleSignIn] intent flow (stable, works without a
 * server client ID). Token minting: Play Services **Authorization API**
 * (`AuthorizationClient.authorize`) — the supported replacement for the removed
 * `GoogleAuthUtil.getToken`; it returns a Drive-scoped access token directly,
 * silently when consent already exists.
 *
 * Flow:
 *  1. [requestAccount]: interactive account picker (UI launches via ActivityResult).
 *  2. [accessToken]: silent-first; on missing consent returns [ConsentRequired]
 *     carrying a PendingIntent for the UI to launch, then retry succeeds.
 */
class DriveAuthManager(
    private val context: Context,
    private val preferences: PreferencesRepository,
) {

    private val driveScope = Scope(SCOPE_DRIVE_APPDATA)

    private val signInOptions: GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()

    /** Last account chosen in the picker; null before first sign-in. */
    fun lastSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    /** Intent for the interactive account-picker; launch from an ActivityResultLauncher. */
    fun signInIntent(): Intent =
        GoogleSignIn.getClient(context, signInOptions).signInIntent

    /** Resolves the account from a completed [signInIntent] result. */
    fun resolveConsent(data: Intent?): GoogleSignInAccount? =
        GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)

    /**
     * Silent-first access-token fetch for [account].
     *
     * @throws ConsentRequired when the user must approve the Drive scope;
     * launch [ConsentRequired.pendingIntent] and call again after resolution.
     * @throws IOException on transport failures.
     */
    suspend fun accessToken(account: Account): String = withContext(Dispatchers.IO) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .setAccount(account)
            .build()

        val result = suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(context)
                .authorize(request)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener {
                    if (!cont.isActive) return@addOnFailureListener
                    // Surface Google's status code — a bare ApiException has a
                    // null message. Status 10 (DEVELOPER_ERROR) almost always
                    // means this APK's package/SHA-1 isn't registered in the
                    // Cloud console's Android OAuth client.
                    val api = it as? com.google.android.gms.common.api.ApiException
                    android.util.Log.w("OpenSrsAuth", "authorize() failed", it)
                    cont.resumeWithException(
                        IOException("Google authorization failed (status ${api?.statusCode ?: "?"})"),
                    )
                }
        }

        val token = result.accessToken
        if (!token.isNullOrEmpty()) return@withContext token

        val pi: PendingIntent = result.pendingIntent
            ?: throw IOException("Drive authorization returned no token and no consent intent")
        throw ConsentRequired(pi)
    }

    /** Carries the PendingIntent the caller must launch to obtain scope consent. */
    class ConsentRequired(val pendingIntent: PendingIntent) :
        Exception("Interactive Google consent required")

    /** Stores the account email so background workers can mint tokens without UI. */
    suspend fun persistAccount(account: GoogleSignInAccount) {
        val email = requireNotNull(account.email) { "Sign-in returned no email" }
        preferences.setSyncMetadata(account = email, lastSyncAt = null, backupUpdatedAt = null)
    }

    suspend fun signOut() {
        runCatching { GoogleSignIn.getClient(context, signInOptions).signOut() }
        preferences.clearAccount()
    }

    companion object {
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

        /** Convenience for resolving an [Account] from the stored email. */
        fun accountFor(email: String): Account = Account(email, ACCOUNT_TYPE)
    }
}

private const val ACCOUNT_TYPE = "com.google"
