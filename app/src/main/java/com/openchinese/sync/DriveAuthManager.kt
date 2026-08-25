package com.openchinese.sync

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.openchinese.data.local.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Google identity for the Drive `appDataFolder` scope via Google Play Services'
 * silent-first authorization ([GoogleAuthUtil]).
 *
 * Flow:
 *  1. [requestAccount]: one-time interactive consent (GoogleSignIn intent) — the UI
 *     layer launches this from Settings and delivers the result back here.
 *  2. [accessToken]: silent token fetch on every sync; Play Services caches and
 *     refreshes automatically while consent holds.
 *
 * No server client ID is needed because we request an OAuth access token, not an
 * ID token for exchange.
 */
class DriveAuthManager(
    private val context: Context,
    private val preferences: PreferencesRepository,
) {

    /** Options requesting exactly the Drive appdata scope. */
    private val signInOptions: GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveAuthManager.DRIVE_APPDATA_SCOPE))
            .requestEmail()
            .build()

    /** The signed-in account, or null when the user hasn't connected yet. */
    fun lastSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    /**
     * True when a previous consent covers the Drive scope and tokens can be
     * fetched silently.
     */
    fun hasDriveAccess(): Boolean {
        val acct = lastSignedInAccount() ?: return false
        return GoogleSignIn.hasPermissions(acct, Scope(DRIVE_APPDATA_SCOPE))
    }

    /**
     * Silent-first authorization. Throws [NeedUserConsent] when Play Services
     * requires the interactive flow; callers then launch
     * `GoogleSignIn.getClient(context, signInOptions).signInIntent`.
     */
    @Throws(NeedUserConsent::class)
    suspend fun accessToken(account: Account): String = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.getToken(
                context,
                account,
                "oauth2:$DRIVE_APPDATA_SCOPE",
            )
        } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
            // e.intent can be surfaced by the UI for one-tap approval.
            throw NeedUserConsent(e.intent)
        } catch (e: IOException) {
            throw IOException("Network failure fetching Drive token", e)
        } catch (e: GoogleAuthException) {
            throw IllegalStateException("Google auth rejected drive.appdata scope", e)
        }
    }

    /**
     * Called with the result of the interactive sign-in / consent flows. Stores the
     * account name in preferences so workers can resolve it without UI.
     */
    suspend fun persistAccount(account: GoogleSignInAccount) {
        val email = requireNotNull(account.email) { "Sign-in returned no email" }
        preferences.setSyncMetadata(
            account = email,
            lastSyncAt = null,
            backupUpdatedAt = null,
        )
    }

    suspend fun signOut() {
        GoogleSignIn.getClient(context, signInOptions).signOut().await()
        preferences.clearAccount()
    }

    /** Carries the Activity-result intent the caller must launch. */
    class NeedUserConsent(val intent: android.content.Intent) :
        Exception("Interactive Google consent required")

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        /** Resolves the stored account for background workers. */
        fun storedAccount(preferences: PreferencesRepository): kotlinx.coroutines.flow.Flow<String?> =
            preferences.driveAccount

        private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                addOnSuccessListener { if (cont.isActive) cont.resumeWith(Result.success(it)) }
                addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
                addOnCanceledListener { if (cont.isActive) cont.cancel() }
            }
    }
}
