package com.example.membership

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val Context.membershipDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "amkt_membership_prefs")

/**
 * Handles owner-only network calls for:
 * 1. Per-device access code validation against https://n8n.marratethu.web.id/webhook/amkt-noads-validate
 * 2. Fetching the owner's remote announcement banner JSON (image + link)
 *
 * Strictly zero third-party ad networks, zero analytics, and zero tracking.
 */
class MembershipRepository(
  private val context: Context,
  private val httpClient: OkHttpClient = defaultHttpClient(),
) {
  private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val validateReqAdapter = moshi.adapter(CodeValidateRequest::class.java)
  private val validateResAdapter = moshi.adapter(CodeValidateResponse::class.java)
  private val bannerAdapter = moshi.adapter(OwnerBannerPayloadDto::class.java)

  @SuppressLint("HardwareIds")
  fun getDeviceId(): String {
    val raw =
      try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
      } catch (_: Exception) {
        null
      }
    return raw?.takeIf { it.isNotBlank() } ?: "amkt-device-unknown"
  }

  val passStatusFlow: Flow<PassStatus> =
    context.membershipDataStore.data.map { prefs ->
      PassStatus(
        isActive = prefs[KEY_PASS_ACTIVE] ?: false,
        code = prefs[KEY_PASS_CODE].orEmpty(),
        expiresAt = prefs[KEY_PASS_EXPIRES_AT].orEmpty(),
        message = prefs[KEY_PASS_MESSAGE].orEmpty(),
        deviceId = getDeviceId(),
      )
    }

  val bannerConfigUrlFlow: Flow<String> =
    context.membershipDataStore.data.map { prefs -> prefs[KEY_BANNER_CONFIG_URL].orEmpty() }

  val ownerBannerFlow: Flow<OwnerBanner?> =
    context.membershipDataStore.data.map { prefs ->
      val img = prefs[KEY_BANNER_IMAGE_URL].orEmpty()
      val link = prefs[KEY_BANNER_LINK_URL].orEmpty()
      val title = prefs[KEY_BANNER_TITLE].orEmpty()
      val subtitle = prefs[KEY_BANNER_SUBTITLE].orEmpty()
      if (img.isBlank() && link.isBlank() && title.isBlank()) null
      else OwnerBanner(imageUrl = img, linkUrl = link, title = title, subtitle = subtitle)
    }

  suspend fun validateCodeWithOwnerServer(rawCode: String): Result<CodeValidateResponse> {
    val trimmedCode = rawCode.trim()
    if (trimmedCode.isBlank()) {
      return Result.failure(IllegalArgumentException("Access Code ထည့်သွင်းပါ"))
    }
    val deviceId = getDeviceId()
    return withContext(Dispatchers.IO) {
      try {
        val jsonBody =
          validateReqAdapter.toJson(CodeValidateRequest(code = trimmedCode, deviceId = deviceId))
        val request =
          Request.Builder()
            .url(VALIDATE_ENDPOINT)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
          val bodyText = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            return@withContext Result.failure(
              IOException("HTTP ${response.code}: ${bodyText.take(120)}")
            )
          }
          val parsed =
            parseValidateResponse(bodyText)
              ?: return@withContext Result.failure(
                IOException("ဆာဗာမှ မမှန်ကန်သော JSON ပြန်လာပါသည်")
              )

          context.membershipDataStore.edit { prefs ->
            prefs[KEY_PASS_ACTIVE] = parsed.valid
            if (parsed.valid) {
              prefs[KEY_PASS_CODE] = trimmedCode
              prefs[KEY_PASS_EXPIRES_AT] = parsed.expiresAt
              prefs[KEY_PASS_MESSAGE] = parsed.message
            } else {
              prefs.remove(KEY_PASS_CODE)
              prefs[KEY_PASS_EXPIRES_AT] = ""
              prefs[KEY_PASS_MESSAGE] = parsed.message
            }
          }
          Result.success(parsed)
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }

  suspend fun clearSavedCode() {
    context.membershipDataStore.edit { prefs ->
      prefs[KEY_PASS_ACTIVE] = false
      prefs.remove(KEY_PASS_CODE)
      prefs.remove(KEY_PASS_EXPIRES_AT)
      prefs.remove(KEY_PASS_MESSAGE)
    }
  }

  suspend fun fetchOwnerBannerFromUrl(rawUrl: String): Result<OwnerBanner?> {
    val cleanUrl = rawUrl.trim()
    if (cleanUrl.isBlank()) {
      context.membershipDataStore.edit { prefs ->
        prefs[KEY_BANNER_CONFIG_URL] = ""
        prefs.remove(KEY_BANNER_IMAGE_URL)
        prefs.remove(KEY_BANNER_LINK_URL)
        prefs.remove(KEY_BANNER_TITLE)
        prefs.remove(KEY_BANNER_SUBTITLE)
      }
      return Result.success(null)
    }
    if (!cleanUrl.startsWith("https://", ignoreCase = true) &&
      !cleanUrl.startsWith("http://", ignoreCase = true)
    ) {
      return Result.failure(IllegalArgumentException("https:// သို့မဟုတ် http:// URL ထည့်သွင်းပါ"))
    }

    return withContext(Dispatchers.IO) {
      try {
        val request =
          Request.Builder().url(cleanUrl).get().header("Accept", "application/json").build()
        httpClient.newCall(request).execute().use { response ->
          val bodyText = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            return@withContext Result.failure(IOException("HTTP ${response.code}"))
          }
          val resolved = parseBannerResponse(bodyText)
          context.membershipDataStore.edit { prefs ->
            prefs[KEY_BANNER_CONFIG_URL] = cleanUrl
            if (resolved != null) {
              prefs[KEY_BANNER_IMAGE_URL] = resolved.imageUrl
              prefs[KEY_BANNER_LINK_URL] = resolved.linkUrl
              prefs[KEY_BANNER_TITLE] = resolved.title
              prefs[KEY_BANNER_SUBTITLE] = resolved.subtitle
            } else {
              prefs.remove(KEY_BANNER_IMAGE_URL)
              prefs.remove(KEY_BANNER_LINK_URL)
              prefs.remove(KEY_BANNER_TITLE)
              prefs.remove(KEY_BANNER_SUBTITLE)
            }
          }
          Result.success(resolved)
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }

  internal fun parseValidateResponse(json: String): CodeValidateResponse? {
    return try {
      validateResAdapter.fromJson(json)
    } catch (_: Exception) {
      null
    }
  }

  internal fun parseBannerResponse(json: String): OwnerBanner? {
    return try {
      bannerAdapter.fromJson(json)?.resolve()
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    const val VALIDATE_ENDPOINT = "https://n8n.marratethu.web.id/webhook/amkt-noads-validate"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val KEY_PASS_ACTIVE = booleanPreferencesKey("pass_active")
    private val KEY_PASS_CODE = stringPreferencesKey("pass_code")
    private val KEY_PASS_EXPIRES_AT = stringPreferencesKey("pass_expires_at")
    private val KEY_PASS_MESSAGE = stringPreferencesKey("pass_message")
    private val KEY_BANNER_CONFIG_URL = stringPreferencesKey("owner_banner_config_url")
    private val KEY_BANNER_IMAGE_URL = stringPreferencesKey("owner_banner_image_url")
    private val KEY_BANNER_LINK_URL = stringPreferencesKey("owner_banner_link_url")
    private val KEY_BANNER_TITLE = stringPreferencesKey("owner_banner_title")
    private val KEY_BANNER_SUBTITLE = stringPreferencesKey("owner_banner_subtitle")

    fun defaultHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
  }
}
