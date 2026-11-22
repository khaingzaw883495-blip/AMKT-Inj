package com.example.redeem

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
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

private val Context.redeemDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "amkt_redeem_prefs")

/**
 * Handles buyer redeem-code submission (`POST {"code": "...", "device_id": "..."}`) against an
 * editable webhook URL persisted in DataStore.
 *
 * Treats empty HTTP 200 responses and malformed JSON as invalid without throwing or creating any
 * server row.
 */
class RedeemRepository(
  private val context: Context,
  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .build(),
  private val dataStore: DataStore<Preferences> = context.redeemDataStore,
) {
  private val moshi: Moshi = Moshi.Builder().build()
  private val requestAdapter = moshi.adapter(RedeemRequestDto::class.java)
  private val responseAdapter = moshi.adapter(RedeemResponseDto::class.java)

  val redeemWebhookUrlFlow: Flow<String> =
    dataStore.data.map { prefs ->
      prefs[KEY_REDEEM_WEBHOOK_URL]?.trim().orEmpty().ifBlank { DEFAULT_REDEEM_WEBHOOK_URL }
    }

  suspend fun getRedeemWebhookUrl(): String = redeemWebhookUrlFlow.first()

  suspend fun saveRedeemWebhookUrl(rawUrl: String): Result<String> {
    val trimmed = rawUrl.trim()
    val effective = if (trimmed.isBlank()) DEFAULT_REDEEM_WEBHOOK_URL else trimmed
    val validationError = validateWebhookUrl(effective)
    if (validationError != null) {
      return Result.failure(IllegalArgumentException(validationError))
    }
    dataStore.edit { prefs ->
      prefs[KEY_REDEEM_WEBHOOK_URL] = effective
    }
    return Result.success(effective)
  }

  suspend fun resetWebhookUrlToDefault(): String {
    dataStore.edit { prefs ->
      prefs[KEY_REDEEM_WEBHOOK_URL] = DEFAULT_REDEEM_WEBHOOK_URL
    }
    return DEFAULT_REDEEM_WEBHOOK_URL
  }

  suspend fun redeemCode(
    rawCode: String,
    deviceId: String,
    webhookUrlOverride: String? = null,
  ): RedeemOutcome =
    withContext(Dispatchers.IO) {
      val normalizedCode = RedeemCodeMapper.normalizeCode(rawCode)
      if (normalizedCode.isBlank()) {
        return@withContext RedeemOutcome.Error("ကုတ် (Redeem Code) ထည့်သွင်းပါ")
      }

      val targetUrl =
        webhookUrlOverride?.trim()?.ifBlank { null } ?: getRedeemWebhookUrl()
      val urlError = validateWebhookUrl(targetUrl)
      if (urlError != null) {
        return@withContext RedeemOutcome.Error(urlError)
      }

      val payloadJson =
        requestAdapter.toJson(
          RedeemRequestDto(
            code = normalizedCode,
            deviceId = deviceId.trim(),
          )
        )
      val request =
        Request.Builder()
          .url(targetUrl)
          .header("Accept", "application/json")
          .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
          .build()

      try {
        httpClient.newCall(request).execute().use { response ->
          val bodyText = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            // Try to surface any JSON message even on non-2xx responses
            val serverMessage =
              tryParseResponse(bodyText)?.message?.trim().orEmpty()
            val errorMsg =
              if (serverMessage.isNotBlank()) serverMessage
              else "ဆာဗာမှ HTTP ${response.code} အမှားပြန်လာပါသည်"
            return@withContext RedeemOutcome.Error(errorMsg)
          }

          if (bodyText.isBlank()) {
            return@withContext RedeemOutcome.Invalid(
              "ကုတ် မတွေ့ပါ သို့မဟုတ် ဆာဗာမှ အချက်အလက် မပြန်လာပါ"
            )
          }

          val parsed =
            tryParseResponse(bodyText)
              ?: return@withContext RedeemOutcome.Invalid(
                "ဆာဗာမှ ပြန်လာသော အချက်အလက် ပုံစံ မမှန်ကန်ပါ (JSON မဟုတ်ပါ)"
              )

          val valid =
            parsed.valid
              ?: return@withContext RedeemOutcome.Invalid(
                parsed.message?.trim().orEmpty().ifBlank {
                  "ဆာဗာမှ ပြန်လာသော အတည်ပြုချက် မပြည့်စုံပါ"
                }
              )

          val replyMessage = parsed.message?.trim().orEmpty()
          if (!valid) {
            return@withContext RedeemOutcome.Invalid(
              replyMessage.ifBlank {
                "ကုတ် မမှန်ကန်ပါ၊ သက်တမ်းကုန်ဆုံးပါပြီ သို့မဟုတ် အခြားဖုန်းတွင် အသုံးပြုပြီးသား ဖြစ်နေပါသည်"
              }
            )
          }

          val serverDto =
            parsed.server
              ?: return@withContext RedeemOutcome.Error(
                replyMessage.ifBlank {
                  "ကုတ် အတည်ပြုသော်လည်း ဆာဗာအချက်အလက် မပါဝင်ပါ"
                }
              )

          val mapped = RedeemCodeMapper.mapServerDtoToEntity(normalizedCode, serverDto)
          mapped.fold(
            onSuccess = { entity ->
              RedeemOutcome.Success(
                code = normalizedCode,
                message = replyMessage.ifBlank { "ကုတ် အတည်ပြုပြီး ဆာဗာကို ထည့်သွင်းပြီးပါပြီ" },
                server = entity,
              )
            },
            onFailure = { err ->
              RedeemOutcome.Error(err.message ?: "ဆာဗာအချက်အလက် ဖတ်မရပါ")
            },
          )
        }
      } catch (_: UnknownHostException) {
        RedeemOutcome.Error("အင်တာနက် ချိတ်ဆက်မှု သို့မဟုတ် Webhook Domain ကို ရှာမတွေ့ပါ")
      } catch (_: SocketTimeoutException) {
        RedeemOutcome.Error("ဆာဗာ တုံ့ပြန်ချိန် ကျော်လွန်သွားပါသည် (Timeout) — ထပ်မံကြိုးစားပါ")
      } catch (_: ConnectException) {
        RedeemOutcome.Error("Redeem ဆာဗာသို့ ချိတ်ဆက်၍ မရပါ")
      } catch (e: IOException) {
        RedeemOutcome.Error("ကွန်ရက် ချိတ်ဆက်မှု အမှား: ${e.message ?: "IO Error"}")
      } catch (e: Exception) {
        RedeemOutcome.Error("ကုတ် စစ်ဆေးမှု မအောင်မြင်ပါ: ${e.message ?: "Unknown Error"}")
      }
    }

  private fun tryParseResponse(bodyText: String): RedeemResponseDto? {
    if (bodyText.isBlank()) return null
    return try {
      responseAdapter.fromJson(bodyText)
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    const val DEFAULT_REDEEM_WEBHOOK_URL =
      "https://n8n.marratethu.web.id/webhook/amkt-injector-redeem"

    private val KEY_REDEEM_WEBHOOK_URL = stringPreferencesKey("redeem_webhook_url")
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun validateWebhookUrl(url: String): String? {
      val trimmed = url.trim()
      if (trimmed.isBlank()) return "Redeem Webhook URL ထည့်သွင်းပါ"
      val uri =
        try {
          URI(trimmed)
        } catch (_: Exception) {
          return "Webhook URL ပုံစံ မမှန်ကန်ပါ"
        }
      val scheme = uri.scheme?.lowercase().orEmpty()
      if (scheme != "http" && scheme != "https") {
        return "Webhook URL သည် http:// သို့မဟုတ် https:// ဖြင့် စတင်ရပါမည်"
      }
      if (uri.host.isNullOrBlank()) {
        return "Webhook URL တွင် ဆာဗာလိပ်စာ (Host) မပါဝင်ပါ"
      }
      return null
    }
  }
}
