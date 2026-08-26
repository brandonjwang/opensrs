package com.opensrs.ui.settings

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.opensrs.OpenSrsApp
import com.opensrs.data.local.DialectMode
import com.opensrs.data.local.PreferencesRepository
import com.opensrs.data.local.RomanizationPref
import com.opensrs.data.local.UserSettings
import com.opensrs.sync.DriveAuthManager
import com.opensrs.sync.DriveSyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferences: PreferencesRepository,
    private val authManager: DriveAuthManager,
    private val syncEngine: DriveSyncEngine,
) : ViewModel() {
    data class SignInState(
        val inFlight: Boolean = false,
        val error: String? = null,
        /** Launch via ActivityResultLauncher, then call [onConsentResult]. */
        val consentIntent: android.content.Intent? = null,
    )

    val settings: StateFlow<UserSettings?> = preferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncStatus: StateFlow<DriveSyncEngine.SyncStatus> = syncEngine.status

    /** Non-null while Google's Drive-scope approval dialog should be shown. */
    val consentRequired: StateFlow<IntentSender?> = syncEngine.consentRequired

    val account: StateFlow<String?> = preferences.driveAccount
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _signIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = _signIn

    fun setDailyNewLimit(v: Int) = viewModelScope.launch { preferences.setDailyNewLimit(v) }
    fun setDailyReviewLimit(v: Int) = viewModelScope.launch { preferences.setDailyReviewLimit(v) }
    fun setHskMaxLevel(v: Int) = viewModelScope.launch { preferences.setHskMaxLevel(v) }
    fun setDialectMode(m: DialectMode) = viewModelScope.launch { preferences.setDialectMode(m) }
    fun setRomanization(r: RomanizationPref) = viewModelScope.launch { preferences.setRomanization(r) }
    fun setAutoPlayTts(v: Boolean) = viewModelScope.launch { preferences.setAutoPlayTts(v) }

    /** Manual "Sync now" button. */
    fun syncNow() = viewModelScope.launch { syncEngine.syncNow() }

    /** Start band; keeps the window valid by raising max when min exceeds it. */
    fun setHskMinLevel(v: Int) = viewModelScope.launch {
        val s = preferences.settingsSnapshot()
        val min = v.coerceIn(0, 7)
        preferences.setHskMinLevel(min)
        if (s.hskMaxLevel in 1 until min) {
            preferences.setHskMaxLevel(min)
        }
    }
    /**
     * Starts Google sign-in. When Play Services already holds consent this
     * completes silently; otherwise the account-picker intent is emitted and
     * the UI must launch it via ActivityResultLauncher then call [onConsentResult].
     */
    fun signIn() {
        viewModelScope.launch {
            _signIn.value = SignInState(inFlight = true)
            try {
                val account = authManager.lastSignedInAccount()
                if (account?.email == null) {
                    _signIn.value = SignInState(consentIntent = authManager.signInIntent())
                    return@launch
                }
                authManager.persistAccount(account)
                _signIn.value = SignInState(inFlight = false)
                // May surface [consentRequired]; the screen launches it and
                // routes the approval back through [onConsentResult].
                syncNow()
            } catch (e: Exception) {
                _signIn.value = SignInState(error = e.message ?: "Sign-in failed")
            }
        }
    }

    /**
     * Result of whichever interactive Google flow was launched:
     * the account picker ([androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult])
     * or the Drive-scope approval dialog (…StartIntentSenderForResult, data is null there).
     * A cancellation must NOT retry — otherwise a dismissed approval dialog
     * re-launches itself forever.
     */
    fun onConsentResult(data: Intent?, approved: Boolean = true) {
        if (!approved) {
            syncEngine.dismissConsent()
            viewModelScope.launch {
                val hadAccount = preferences.driveAccount.first() != null
                _signIn.value = SignInState(
                    error = if (hadAccount) DriveSyncEngine.CONSENT_MSG else "Sign-in canceled",
                )
            }
            return
        }
        viewModelScope.launch {
            try {
                var email: String? = null
                if (data != null) {
                    runCatching { authManager.resolveConsent(data) }
                        .getOrNull()
                        ?.let { account ->
                            if (account.email != null) {
                                authManager.persistAccount(account)
                                email = account.email
                            }
                        }
                }
                if (email == null && preferences.driveAccount.first() == null) {
                    _signIn.value = SignInState(error = "Sign-in canceled")
                    return@launch
                }
                _signIn.value = SignInState(inFlight = false)
                syncNow() // consent granted -> token mints silently this time
            } catch (e: Exception) {
                _signIn.value = SignInState(error = e.message ?: "Sign-in failed")
            }
        }
    }

    fun signOut() = viewModelScope.launch {
        authManager.signOut()
        _signIn.value = SignInState()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenSrsApp
                SettingsViewModel(
                    preferences = app.container.preferences,
                    authManager = DriveAuthManager(app, app.container.preferences),
                    syncEngine = app.container.syncEngine,
                )
            }
        }
    }
}
