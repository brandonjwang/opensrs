package com.opensrs.ui.settings

import android.content.Intent
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

    val account: StateFlow<String?> = preferences.driveAccount
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _signIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = _signIn

    fun setDailyNewLimit(v: Int) = viewModelScope.launch { preferences.setDailyNewLimit(v) }
    fun setDailyReviewLimit(v: Int) = viewModelScope.launch { preferences.setDailyReviewLimit(v) }
    fun setDialectMode(m: DialectMode) = viewModelScope.launch { preferences.setDialectMode(m) }
    fun setRomanization(r: RomanizationPref) = viewModelScope.launch { preferences.setRomanization(r) }
    fun setAutoPlayTts(v: Boolean) = viewModelScope.launch { preferences.setAutoPlayTts(v) }

    /** Manual "Sync now" button. */
    fun syncNow() = viewModelScope.launch { syncEngine.syncNow() }

    /**
     * Starts Google sign-in. When Play Services already holds consent this
     * completes silently; otherwise [SignInState.needsConsentIntent] is emitted and
     * the UI must launch it via `ActivityResultLauncher` then call [onConsentResult].
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
                syncNow()
            } catch (e: Exception) {
                _signIn.value = SignInState(error = e.message ?: "Sign-in failed")
            }
        }
    }

    /** Feed the ActivityResult from launching the account-picker intent here. */
    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            try {
                val account = authManager.resolveConsent(data)
                if (account?.email != null) {
                    authManager.persistAccount(account)
                    _signIn.value = SignInState(inFlight = false)
                    syncNow()
                } else {
                    _signIn.value = SignInState(error = "Sign-in canceled")
                }
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
