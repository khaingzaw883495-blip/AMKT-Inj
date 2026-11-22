package com.example.vpn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.vpnPrefsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "amkt_vpn_prefs")

data class VpnProtectionSettings(
  val killSwitchEnabled: Boolean = true,
  val autoReconnectEnabled: Boolean = true,
)

class VpnPreferencesRepository(private val context: Context) {
  val settingsFlow: Flow<VpnProtectionSettings> =
    context.vpnPrefsDataStore.data.map { prefs ->
      VpnProtectionSettings(
        killSwitchEnabled = prefs[KEY_KILL_SWITCH] ?: true,
        autoReconnectEnabled = prefs[KEY_AUTO_RECONNECT] ?: true,
      )
    }

  suspend fun currentSettings(): VpnProtectionSettings = settingsFlow.first()

  suspend fun setKillSwitchEnabled(enabled: Boolean) {
    context.vpnPrefsDataStore.edit { prefs -> prefs[KEY_KILL_SWITCH] = enabled }
  }

  suspend fun setAutoReconnectEnabled(enabled: Boolean) {
    context.vpnPrefsDataStore.edit { prefs -> prefs[KEY_AUTO_RECONNECT] = enabled }
  }

  companion object {
    private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch_enabled")
    private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect_enabled")
  }
}
