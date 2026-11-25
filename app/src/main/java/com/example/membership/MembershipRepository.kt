package com.example.membership

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ads.AdsterraConfig
import com.example.ads.FreeTimeStatus
import com.example.data.ProfileExpiryChecker
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
  private val dataStore: DataStore<Preferences> = context.membershipDataStore,
  private val todayYmdProvider: () -> Int = { ProfileExpiryChecker.currentLocalYmd() },
  private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
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
    dataStore.data.map { prefs ->
      PassStatus(
        isActive = prefs[KEY_PASS_ACTIVE] ?: false,
        code = prefs[KEY_PASS_CODE].orEmpty(),
        expiresAt = prefs[KEY_PASS_EXPIRES_AT].orEmpty(),
        message = prefs[KEY_PASS_MESSAGE].orEmpty(),
        deviceId = getDeviceId(),
      )
    }

  val bannerConfigUrlFlow: Flow<String> =
    dataStore.data.map { prefs -> prefs[KEY_BANNER_CONFIG_URL].orEmpty() }

  val ownerBannerFlow: Flow<OwnerBanner?> =
    dataStore.data.map { prefs ->
      val img = OwnerBanner.sanitizeHttpUrl(prefs[KEY_BANNER_IMAGE_URL].orEmpty())
      val link = OwnerBanner.sanitizeHttpUrl(prefs[KEY_BANNER_LINK_URL].orEmpty())
      val title = prefs[KEY_BANNER_TITLE].orEmpty().trim()
      val subtitle = prefs[KEY_BANNER_SUBTITLE].orEmpty().trim()
      if (img.isBlank() && link.isBlank() && title.isBlank()) null
      else OwnerBanner(imageUrl = img, linkUrl = link, title = title, subtitle = subtitle)
    }

  val adGateDailyCountFlow: Flow<Int> =
    dataStore.data.map { prefs ->
      val today = todayYmdProvider()
      val storedDate = prefs[KEY_AD_GATE_DATE_YMD] ?: 0
      if (storedDate == today) (prefs[KEY_AD_GATE_DAILY_COUNT] ?: 0).coerceAtLeast(0) else 0
    }

  val freeTimeStatusFlow: Flow<FreeTimeStatus> =
    dataStore.data.map { prefs ->
      val today = todayYmdProvider()
      val storedDate = prefs[KEY_TIME_EXTEND_DATE_YMD] ?: 0
      val usedToday =
        if (storedDate == today) (prefs[KEY_TIME_EXTEND_DAILY_COUNT] ?: 0).coerceAtLeast(0) else 0
      FreeTimeStatus(
        initialized = prefs[KEY_FREE_TIME_INITIALIZED] ?: false,
        expiresAtMs = prefs[KEY_FREE_TIME_EXPIRES_AT_MS] ?: 0L,
        grantsUsedToday = usedToday,
      )
    }

  suspend fun getFreeTimeStatus(todayYmd: Int = todayYmdProvider()): FreeTimeStatus {
    val prefs = dataStore.data.first()
    val storedDate = prefs[KEY_TIME_EXTEND_DATE_YMD] ?: 0
    val usedToday =
      if (storedDate == todayYmd) (prefs[KEY_TIME_EXTEND_DAILY_COUNT] ?: 0).coerceAtLeast(0) else 0
    return FreeTimeStatus(
      initialized = prefs[KEY_FREE_TIME_INITIALIZED] ?: false,
      expiresAtMs = prefs[KEY_FREE_TIME_EXPIRES_AT_MS] ?: 0L,
      grantsUsedToday = usedToday,
    )
  }

  /**
   * Grants the initial 6 hours of free VPN time on the user's first free connect without consuming
   * any of the 2 daily time-extension grants. No-op if already initialized.
   */
  suspend fun ensureInitialFreeTimeIfNeeded(
    nowMs: Long = nowMsProvider(),
    todayYmd: Int = todayYmdProvider(),
  ): FreeTimeStatus {
    var result = FreeTimeStatus()
    dataStore.edit { prefs ->
      val storedDate = prefs[KEY_TIME_EXTEND_DATE_YMD] ?: 0
      val usedToday =
        if (storedDate == todayYmd) (prefs[KEY_TIME_EXTEND_DAILY_COUNT] ?: 0).coerceAtLeast(0) else 0
      val alreadyInit = prefs[KEY_FREE_TIME_INITIALIZED] ?: false
      val expiresAt =
        if (alreadyInit) {
          prefs[KEY_FREE_TIME_EXPIRES_AT_MS] ?: 0L
        } else {
          val initialExpiry = nowMs + AdsterraConfig.INITIAL_FREE_MS
          prefs[KEY_FREE_TIME_INITIALIZED] = true
          prefs[KEY_FREE_TIME_EXPIRES_AT_MS] = initialExpiry
          initialExpiry
        }
      result =
        FreeTimeStatus(
          initialized = true,
          expiresAtMs = expiresAt,
          grantsUsedToday = usedToday,
        )
    }
    return result
  }

  /**
   * Atomically records one +9h time-extension grant watch on its OWN daily counter (max 2/day per
   * device, separate from the unlimited connect gate; resets at midnight local time).
   * Refuses a 3rd grant on the same local day with [Result.failure].
   */
  suspend fun recordAdWatchAndGrantFreeTime(
    nowMs: Long = nowMsProvider(),
    todayYmd: Int = todayYmdProvider(),
  ): Result<FreeTimeStatus> {
    var refused = false
    var updated = FreeTimeStatus()
    dataStore.edit { prefs ->
      val storedDate = prefs[KEY_TIME_EXTEND_DATE_YMD] ?: 0
      val currentUsed =
        if (storedDate == todayYmd) (prefs[KEY_TIME_EXTEND_DAILY_COUNT] ?: 0).coerceAtLeast(0) else 0
      if (currentUsed >= AdsterraConfig.MAX_TIME_EXTEND_GRANTS_PER_DAY) {
        refused = true
        updated =
          FreeTimeStatus(
            initialized = prefs[KEY_FREE_TIME_INITIALIZED] ?: true,
            expiresAtMs = prefs[KEY_FREE_TIME_EXPIRES_AT_MS] ?: 0L,
            grantsUsedToday = currentUsed,
          )
        return@edit
      }
      val nextUsed = currentUsed + 1
      val currentExpiry = prefs[KEY_FREE_TIME_EXPIRES_AT_MS] ?: 0L
      val newExpiry = maxOf(nowMs, currentExpiry) + AdsterraConfig.AD_GRANT_MS
      prefs[KEY_TIME_EXTEND_DATE_YMD] = todayYmd
      prefs[KEY_TIME_EXTEND_DAILY_COUNT] = nextUsed
      prefs[KEY_FREE_TIME_INITIALIZED] = true
      prefs[KEY_FREE_TIME_EXPIRES_AT_MS] = newExpiry
      updated =
        FreeTimeStatus(
          initialized = true,
          expiresAtMs = newExpiry,
          grantsUsedToday = nextUsed,
        )
    }
    return if (refused) {
      Result.failure(
        IllegalStateException(
          "ယနေ့အတွက် ကြော်ငြာကြည့်၍ အချိန်ထပ်တိုးခွင့် (၂ ကြိမ်) ပြည့်သွားပါပြီ"
        )
      )
    } else {
      Result.success(updated)
    }
  }

  suspend fun getAdGateCountForDate(todayYmd: Int = todayYmdProvider()): Int {
    val prefs = dataStore.data.first()
    val storedDate = prefs[KEY_AD_GATE_DATE_YMD] ?: 0
    return if (storedDate == todayYmd) (prefs[KEY_AD_GATE_DAILY_COUNT] ?: 0).coerceAtLeast(0) else 0
  }

  suspend fun getTimeExtendGrantsUsedToday(todayYmd: Int = todayYmdProvider()): Int {
    val prefs = dataStore.data.first()
    val storedDate = prefs[KEY_TIME_EXTEND_DATE_YMD] ?: 0
    return if (storedDate == todayYmd) (prefs[KEY_TIME_EXTEND_DAILY_COUNT] ?: 0).coerceAtLeast(0) else 0
  }

  /**
   * Records one 7-second Smartlink_2 connect gate showing (unlimited per day, separate from the
   * 2x/day time-extension grant counter).
   */
  suspend fun recordAdGateShown(todayYmd: Int = todayYmdProvider()): Int {
    var updatedCount = 1
    dataStore.edit { prefs ->
      val storedDate = prefs[KEY_AD_GATE_DATE_YMD] ?: 0
      val nextCount =
        if (storedDate == todayYmd) {
          (prefs[KEY_AD_GATE_DAILY_COUNT] ?: 0).coerceAtLeast(0) + 1
        } else {
          1
        }
      prefs[KEY_AD_GATE_DATE_YMD] = todayYmd
      prefs[KEY_AD_GATE_DAILY_COUNT] = nextCount
      updatedCount = nextCount
    }
    return updatedCount
  }

  fun currentLocalYmd(): Int = todayYmdProvider()

  fun currentEpochMs(): Long = nowMsProvider()

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

          dataStore.edit { prefs ->
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
    dataStore.edit { prefs ->
      prefs[KEY_PASS_ACTIVE] = false
      prefs.remove(KEY_PASS_CODE)
      prefs.remove(KEY_PASS_EXPIRES_AT)
      prefs.remove(KEY_PASS_MESSAGE)
    }
  }

  /**
   * Persists the owner announcement banner parsed from the Remote JSON `"ads"` section.
   * Passing `null` clears any stored remote banner so Home falls back to the built-in AMKT
   * Telegram channel banner (`https://t.me/amktvpn`).
   */
  suspend fun saveOwnerBanner(banner: OwnerBanner?) {
    val sanitizedImage = banner?.let { OwnerBanner.sanitizeHttpUrl(it.imageUrl) }.orEmpty()
    val sanitizedLink = banner?.let { OwnerBanner.sanitizeHttpUrl(it.linkUrl) }.orEmpty()
    val title = banner?.title?.trim().orEmpty()
    val subtitle = banner?.subtitle?.trim().orEmpty()
    dataStore.edit { prefs ->
      if (sanitizedImage.isNotBlank() || sanitizedLink.isNotBlank() || title.isNotBlank()) {
        prefs[KEY_BANNER_IMAGE_URL] = sanitizedImage
        prefs[KEY_BANNER_LINK_URL] = sanitizedLink
        prefs[KEY_BANNER_TITLE] = title
        prefs[KEY_BANNER_SUBTITLE] = subtitle
      } else {
        prefs.remove(KEY_BANNER_IMAGE_URL)
        prefs.remove(KEY_BANNER_LINK_URL)
        prefs.remove(KEY_BANNER_TITLE)
        prefs.remove(KEY_BANNER_SUBTITLE)
      }
    }
  }

  suspend fun fetchOwnerBannerFromUrl(rawUrl: String): Result<OwnerBanner?> {
    val cleanUrl = rawUrl.trim()
    if (cleanUrl.isBlank()) {
      dataStore.edit { prefs ->
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
          dataStore.edit { prefs -> prefs[KEY_BANNER_CONFIG_URL] = cleanUrl }
          saveOwnerBanner(resolved)
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
    private val KEY_AD_GATE_DATE_YMD = intPreferencesKey("ad_gate_date_ymd")
    private val KEY_AD_GATE_DAILY_COUNT = intPreferencesKey("ad_gate_daily_count")
    private val KEY_TIME_EXTEND_DATE_YMD = intPreferencesKey("time_extend_date_ymd")
    private val KEY_TIME_EXTEND_DAILY_COUNT = intPreferencesKey("time_extend_daily_count")
    private val KEY_FREE_TIME_INITIALIZED = booleanPreferencesKey("free_time_initialized")
    private val KEY_FREE_TIME_EXPIRES_AT_MS = longPreferencesKey("free_time_expires_at_ms")

    fun defaultHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
  }
}
