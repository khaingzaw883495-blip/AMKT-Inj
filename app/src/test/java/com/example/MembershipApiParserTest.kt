package com.example

import com.example.membership.CodeValidateRequest
import com.example.membership.CodeValidateResponse
import com.example.membership.OwnerBannerPayloadDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipApiParserTest {
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

  @Test
  fun validateRequestAndResponseMatchOwnerWebhookSchema() {
    val reqAdapter = moshi.adapter(CodeValidateRequest::class.java)
    val resAdapter = moshi.adapter(CodeValidateResponse::class.java)

    val jsonReq = reqAdapter.toJson(CodeValidateRequest(code = "AMKT-9988", deviceId = "dev123"))
    assertTrue(jsonReq.contains("\"code\":\"AMKT-9988\""))
    assertTrue(jsonReq.contains("\"device_id\":\"dev123\""))

    val validRes =
      resAdapter.fromJson(
        """{"valid":true,"expires_at":"2026-12-20","message":"Activated for 30 days"}"""
      )
    assertNotNull(validRes)
    assertTrue(validRes!!.valid)
    assertEquals("2026-12-20", validRes.expiresAt)
    assertEquals("Activated for 30 days", validRes.message)

    val invalidRes =
      resAdapter.fromJson("""{"valid":false,"expires_at":"","message":"Code already used"}""")
    assertNotNull(invalidRes)
    assertFalse(invalidRes!!.valid)
    assertEquals("Code already used", invalidRes.message)
  }

  @Test
  fun remoteOwnerBannerSupportsFlatAndNestedSchemas() {
    val adapter = moshi.adapter(OwnerBannerPayloadDto::class.java)

    val flat =
      adapter.fromJson(
        """{"image_url":"https://cdn.example.com/promo.png","link_url":"https://t.me/examplebot","title":"AMKT Notice"}"""
      )
        ?.resolve()
    assertNotNull(flat)
    assertEquals("https://cdn.example.com/promo.png", flat!!.imageUrl)
    assertEquals("https://t.me/examplebot", flat.linkUrl)
    assertEquals("AMKT Notice", flat.title)

    val nested =
      adapter.fromJson(
        """{"own_ad":{"image":"https://cdn.example.com/b.jpg","link":"https://example.com"}}"""
      )
        ?.resolve()
    assertNotNull(nested)
    assertEquals("https://cdn.example.com/b.jpg", nested!!.imageUrl)
    assertEquals("https://example.com", nested.linkUrl)
  }
}
