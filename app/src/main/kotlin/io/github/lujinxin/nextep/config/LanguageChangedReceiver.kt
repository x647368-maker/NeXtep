package io.github.x647368maker.atomicworkbench.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Also synchronizes changes made from Android's per-app language settings. */
class LanguageChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) AppLanguage.notifyChanged(context)
    }
}
