package com.attriax.sdk.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.attriax.sdk.AttriaxBrowserOpener

/**
 * Android [AttriaxBrowserOpener] actual (Flutter
 * `AttriaxPlatform.openBrowserUrl`). Fires an `ACTION_VIEW` intent for the resolved
 * browser-fallback URL. Uses the application context, so `FLAG_ACTIVITY_NEW_TASK` is
 * required (there may be no Activity on the calling stack). Best-effort: a malformed
 * URL or the absence of a handling app yields `false` rather than throwing into the
 * deep-link resolve callback.
 */
class AttriaxAndroidBrowserOpener(context: Context) : AttriaxBrowserOpener {
    private val appContext: Context = context.applicationContext

    override fun open(url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
