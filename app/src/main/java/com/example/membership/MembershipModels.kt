package com.example.membership

import com.example.data.ProfileExpiryChecker
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CodeValidateRequest(
  @Json(name = "code") val code: String,
  @Json(name = "device_id") val deviceId: String,
)

@JsonClass(generateAdapter = true)
data class CodeValidateResponse(
  @Json(name = "valid") val valid: Boolean = false,
  @Json(name = "expires_at") val expiresAt: String = "",
  @Json(name = "message") val message: String = "",
)

@JsonClass(generateAdapter = true)
data class OwnerBannerPayloadDto(
  @Json(name = "image_url") val imageUrl: String = "",
  @Json(name = "image") val imageFallback: String = "",
  @Json(name = "link_url") val linkUrl: String = "",
  @Json(name = "link") val linkFallback: String = "",
  @Json(name = "url") val urlFallback: String = "",
  @Json(name = "title") val title: String = "",
  @Json(name = "subtitle") val subtitle: String = "",
  @Json(name = "ads") val nestedAds: OwnerBannerNestedDto? = null,
  @Json(name = "own_ad") val nestedOwnAd: OwnerBannerNestedDto? = null,
  @Json(name = "banner") val nestedBanner: OwnerBannerNestedDto? = null,
) {
  fun resolve(): OwnerBanner? {
    val nested = nestedAds ?: nestedOwnAd ?: nestedBanner
    val resolvedImage =
      OwnerBanner.sanitizeHttpUrl(
        listOf(imageUrl, imageFallback, nested?.imageUrl.orEmpty(), nested?.imageFallback.orEmpty())
          .firstOrNull { it.isNotBlank() }
          .orEmpty()
      )
    val resolvedLink =
      OwnerBanner.sanitizeHttpUrl(
        listOf(
            linkUrl,
            linkFallback,
            urlFallback,
            nested?.linkUrl.orEmpty(),
            nested?.linkFallback.orEmpty(),
            nested?.urlFallback.orEmpty(),
          )
          .firstOrNull { it.isNotBlank() }
          .orEmpty()
      )
    val resolvedTitle = title.ifBlank { nested?.title.orEmpty() }.trim()
    val resolvedSubtitle = subtitle.ifBlank { nested?.subtitle.orEmpty() }.trim()

    if (resolvedImage.isBlank() && resolvedLink.isBlank() && resolvedTitle.isBlank()) return null
    return OwnerBanner(
      imageUrl = resolvedImage,
      linkUrl = resolvedLink,
      title = resolvedTitle,
      subtitle = resolvedSubtitle,
    )
  }
}

@JsonClass(generateAdapter = true)
data class OwnerBannerNestedDto(
  @Json(name = "image_url") val imageUrl: String = "",
  @Json(name = "image") val imageFallback: String = "",
  @Json(name = "link_url") val linkUrl: String = "",
  @Json(name = "link") val linkFallback: String = "",
  @Json(name = "url") val urlFallback: String = "",
  @Json(name = "title") val title: String = "",
  @Json(name = "subtitle") val subtitle: String = "",
)

data class OwnerBanner(
  val imageUrl: String,
  val linkUrl: String,
  val title: String = "",
  val subtitle: String = "",
) {
  fun effectiveLinkUrl(): String =
    sanitizeHttpUrl(linkUrl).ifBlank { DEFAULT_TELEGRAM_URL }

  companion object {
    const val DEFAULT_TELEGRAM_URL = "https://t.me/amktvpn"

    /**
     * Allows strictly http:// and https:// URLs so untrusted schemes (javascript:, data:, file:,
     * intent:) from remote JSON can never be opened or rendered.
     */
    fun sanitizeHttpUrl(raw: String): String {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return ""
      val lower = trimmed.lowercase()
      return if (lower.startsWith("https://") || lower.startsWith("http://")) trimmed else ""
    }
  }
}

data class PassStatus(
  val isActive: Boolean = false,
  val code: String = "",
  val expiresAt: String = "",
  val message: String = "",
  val deviceId: String = "",
) {
  fun isCurrentlyValid(todayYmd: Int = ProfileExpiryChecker.currentLocalYmd()): Boolean {
    if (!isActive) return false
    val exp = expiresAt.trim()
    if (exp.isBlank()) return true
    return !ProfileExpiryChecker.isExpired(exp, todayYmd)
  }

  fun maskedCode(): String {
    val clean = code.trim()
    if (clean.length <= 4) return clean
    return "${clean.take(2)}${"•".repeat((clean.length - 4).coerceAtMost(8))}${clean.takeLast(2)}"
  }
}

@JsonClass(generateAdapter = true)
data class RemotePriceDto(
  @Json(name = "label") val label: String = "",
  @Json(name = "name") val name: String = "",
  @Json(name = "title") val title: String = "",
  @Json(name = "amount") val amount: Any? = null,
  @Json(name = "price") val price: Any? = null,
  @Json(name = "currency") val currency: String = "",
  @Json(name = "duration") val duration: String = "",
  @Json(name = "period") val period: String = "",
  @Json(name = "server") val server: String = "",
  @Json(name = "package") val packageAlt: String = "",
  @Json(name = "package_name") val packageName: String = "",
) {
  fun resolveOrNull(): RemotePriceItem? {
    val resolvedLabel = listOf(label, name, title).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val resolvedAmount = formatRawAmount(amount ?: price)
    val resolvedCurrency = currency.trim()
    val resolvedDuration = listOf(duration, period).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (
      resolvedLabel.isBlank() ||
        resolvedAmount.isBlank() ||
        resolvedCurrency.isBlank() ||
        resolvedDuration.isBlank()
    ) {
      return null
    }
    val resolvedServer =
      listOf(server, packageName, packageAlt).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return RemotePriceItem(
      label = resolvedLabel,
      amount = resolvedAmount,
      currency = resolvedCurrency,
      duration = resolvedDuration,
      server = resolvedServer,
    )
  }

  companion object {
    internal fun formatRawAmount(raw: Any?): String {
      return when (raw) {
        null -> ""
        is Boolean -> ""
        is Int -> raw.toString()
        is Long -> raw.toString()
        is Number -> {
          val d = raw.toDouble()
          if (d.isNaN() || d.isInfinite()) ""
          else if (d % 1.0 == 0.0) d.toLong().toString()
          else d.toString()
        }
        is String -> raw.trim()
        else -> ""
      }
    }
  }
}

@JsonClass(generateAdapter = true)
data class RemotePriceItem(
  @Json(name = "label") val label: String,
  @Json(name = "amount") val amount: String,
  @Json(name = "currency") val currency: String,
  @Json(name = "duration") val duration: String,
  @Json(name = "server") val server: String = "",
) {
  fun formattedPriceLine(): String = "$amount $currency / $duration"
}

@JsonClass(generateAdapter = true)
data class RemotePriceListEnvelope(
  @Json(name = "prices") val prices: List<RemotePriceItem> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class UsagePingRequest(
  @Json(name = "device_id") val deviceId: String,
  @Json(name = "server_name") val serverName: String = "",
  @Json(name = "event") val event: String = "connect",
)

@JsonClass(generateAdapter = true)
data class UsagePingResponse(
  @Json(name = "app_users") val appUsers: Int? = null,
  @Json(name = "total_app_users") val totalAppUsers: Int? = null,
  @Json(name = "total_users") val totalUsers: Int? = null,
  @Json(name = "server_users") val serverUsers: Int? = null,
  @Json(name = "lifetime_users") val lifetimeUsers: Int? = null,
  @Json(name = "used_by") val usedBy: Int? = null,
  @Json(name = "distinct_users") val distinctUsers: Int? = null,
  @Json(name = "server_name") val serverName: String = "",
)

data class UsagePingResult(
  val totalAppUsers: Int?,
  val serverLifetimeUsers: Int?,
)
