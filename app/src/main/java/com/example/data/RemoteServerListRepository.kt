package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.config.MyVpnConfigDto
import com.example.config.NativeKeyBridge
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private val Context.serverPrefsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "amkt_server_list_prefs")

/**
 * Manages:
 * 1. Owner-configurable Remote JSON Server/Network List URL (defaulting to the app's
 *    built-in list URL so Update / Load works out of the box without pasting, while
 *    staying editable and resettable in About/Settings).
 * 2. Persisting the last-used Network + Server combination across app launches.
 */
class RemoteServerListRepository(
  private val context: Context,
  private val loader: ServerListLoader = ServerListLoader(),
  private val httpClient: OkHttpClient = defaultHttpClient(),
  private val dataStore: DataStore<Preferences> = context.serverPrefsDataStore,
  private val defaultRemoteServerListUrl: String = DEFAULT_REMOTE_SERVER_LIST_URL,
) {
  val remoteServerListUrlFlow: Flow<String> =
    dataStore.data.map { prefs ->
      prefs[KEY_REMOTE_SERVER_LIST_URL]?.trim()?.takeIf { it.isNotBlank() }
        ?: defaultRemoteServerListUrl
    }

  val lastNetworkIdFlow: Flow<Long> =
    dataStore.data.map { prefs -> prefs[KEY_LAST_NETWORK_ID] ?: -1L }

  val lastServerIdFlow: Flow<Long> =
    dataStore.data.map { prefs -> prefs[KEY_LAST_SERVER_ID] ?: -1L }

  suspend fun getLastSelection(): Pair<Long, Long> {
    val prefs = dataStore.data.first()
    return (prefs[KEY_LAST_NETWORK_ID] ?: -1L) to (prefs[KEY_LAST_SERVER_ID] ?: -1L)
  }

  suspend fun saveLastNetworkId(networkId: Long?) {
    dataStore.edit { prefs ->
      prefs[KEY_LAST_NETWORK_ID] = networkId ?: -1L
    }
  }

  suspend fun saveLastServerId(serverId: Long?) {
    dataStore.edit { prefs ->
      prefs[KEY_LAST_SERVER_ID] = serverId ?: -1L
    }
  }

  suspend fun saveLastSelection(networkId: Long?, serverId: Long?) {
    dataStore.edit { prefs ->
      prefs[KEY_LAST_NETWORK_ID] = networkId ?: -1L
      prefs[KEY_LAST_SERVER_ID] = serverId ?: -1L
    }
  }

  suspend fun hasSeededInitialTemplates(): Boolean {
    return dataStore.data.first()[KEY_INITIAL_TEMPLATES_SEEDED] ?: false
  }

  suspend fun markInitialTemplatesSeeded() {
    dataStore.edit { prefs -> prefs[KEY_INITIAL_TEMPLATES_SEEDED] = true }
  }

  suspend fun getSavedRemoteServerListUrl(): String {
    return dataStore.data.first()[KEY_REMOTE_SERVER_LIST_URL]
      ?.trim()
      ?.takeIf { it.isNotBlank() }
      ?: defaultRemoteServerListUrl
  }

  suspend fun saveRemoteServerListUrl(url: String) {
    val clean = url.trim()
    dataStore.edit { prefs ->
      if (clean.isBlank() || clean == defaultRemoteServerListUrl) {
        prefs.remove(KEY_REMOTE_SERVER_LIST_URL)
      } else {
        prefs[KEY_REMOTE_SERVER_LIST_URL] = clean
      }
    }
  }

  suspend fun resetRemoteServerListUrlToDefault(): String {
    dataStore.edit { prefs -> prefs.remove(KEY_REMOTE_SERVER_LIST_URL) }
    return defaultRemoteServerListUrl
  }

  fun isDefaultRemoteServerListUrl(url: String): Boolean {
    val clean = url.trim()
    return clean.isEmpty() || clean == defaultRemoteServerListUrl
  }

  suspend fun fetchRawJsonFromRemoteUrl(rawUrl: String): Result<String> {
    val cleanUrl = rawUrl.trim()
    if (cleanUrl.isBlank()) {
      return Result.failure(IllegalArgumentException("Remote Server JSON URL ထည့်သွင်းပါ"))
    }
    if (
      !cleanUrl.startsWith("https://", ignoreCase = true) &&
        !cleanUrl.startsWith("http://", ignoreCase = true)
    ) {
      return Result.failure(
        IllegalArgumentException(
          "URL ပုံစံ မမှန်ကန်ပါ — https:// သို့မဟုတ် http:// ဖြင့် စတင်သော URL ထည့်သွင်းပါ"
        )
      )
    }
    val parsedUrl = cleanUrl.toHttpUrlOrNull()
    if (parsedUrl == null || parsedUrl.host.isBlank()) {
      return Result.failure(
        IllegalArgumentException("URL ပုံစံ မမှန်ကန်ပါ — မှန်ကန်သော JSON URL ထည့်သွင်းပါ")
      )
    }

    saveRemoteServerListUrl(cleanUrl)

    return withContext(Dispatchers.IO) {
      try {
        val request =
          Request.Builder().url(parsedUrl).get().header("Accept", "application/json").build()
        httpClient.newCall(request).execute().use { response ->
          val body = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              IOException("ဆာဗာမှ အမှားပြန်လာပါသည် (HTTP ${response.code})")
            )
          }
          Result.success(body)
        }
      } catch (e: UnknownHostException) {
        Result.failure(
          IOException("ကွန်ရက် ချိတ်ဆက်မရပါ — အင်တာနက် ချိတ်ဆက်မှုကို စစ်ဆေးပါ", e)
        )
      } catch (e: SocketTimeoutException) {
        Result.failure(IOException("ဆာဗာသို့ ချိတ်ဆက်ချိန် ကျော်လွန်သွားပါသည် (Timeout)", e))
      } catch (e: ConnectException) {
        Result.failure(IOException("ကွန်ရက် ချိတ်ဆက်မရပါ — ဆာဗာမှ ချိတ်ဆက်မှုကို လက်မခံပါ", e))
      } catch (e: Exception) {
        Result.failure(
          IOException("ကွန်ရက် ချိတ်ဆက်မရပါ — အင်တာနက် ချိတ်ဆက်မှုကို စစ်ဆေးပါ", e)
        )
      }
    }
  }

  suspend fun fetchSplitListFromRemoteUrl(rawUrl: String): Result<SplitRemoteListResult> {
    return fetchRawJsonFromRemoteUrl(rawUrl).fold(
      onSuccess = { body -> loader.parseSplitRemoteList(body) },
      onFailure = { err -> Result.failure(err) },
    )
  }

  suspend fun fetchDtosFromRemoteUrl(rawUrl: String): Result<List<MyVpnConfigDto>> {
    return fetchRawJsonFromRemoteUrl(rawUrl).fold(
      onSuccess = { body -> loader.parseServerListDtos(body) },
      onFailure = { err -> Result.failure(err) },
    )
  }

  suspend fun fetchProfilesFromRemoteUrl(rawUrl: String): Result<List<ServerProfileEntity>> {
    return fetchDtosFromRemoteUrl(rawUrl).map { dtos ->
      dtos.mapIndexed { index, dto -> dto.toEntity(fallbackName = "Server #${index + 1}") }
    }
  }

  companion object {
    val DEFAULT_REMOTE_SERVER_LIST_URL: String
      get() = NativeKeyBridge.resolveDefaultRemoteServerListUrl()
    private val KEY_INITIAL_TEMPLATES_SEEDED = booleanPreferencesKey("initial_templates_seeded")
    private val KEY_REMOTE_SERVER_LIST_URL = stringPreferencesKey("remote_server_list_url")
    private val KEY_LAST_NETWORK_ID = longPreferencesKey("last_selected_network_id")
    private val KEY_LAST_SERVER_ID = longPreferencesKey("last_selected_server_id")

    private fun defaultHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
  }
}
