package com.example.analytics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Non-blocking, fail-safe initializer for Firebase Analytics only.
 *
 * Records Firebase's standard automatic engagement/session events (`first_open`, `session_start`,
 * `user_engagement`, `screen_view`) without adding any custom event logging or blocking app launch
 * or VPN connection even when offline or when Google Play Services is unavailable.
 */
object AppAnalyticsInitializer {

  fun initialize(
    context: Context,
    initBlock: (Context) -> FirebaseAnalytics = { ctx ->
      if (FirebaseApp.getApps(ctx).isEmpty()) {
        FirebaseApp.initializeApp(ctx)
      }
      FirebaseAnalytics.getInstance(ctx).apply {
        setAnalyticsCollectionEnabled(true)
      }
    },
  ): Boolean {
    return try {
      initBlock(context)
      true
    } catch (_: Throwable) {
      false
    }
  }
}
