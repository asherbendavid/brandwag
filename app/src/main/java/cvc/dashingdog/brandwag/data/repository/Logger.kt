package cvc.dashingdog.brandwag.data.repository

import android.util.Log

/**
 * Thin wrapper around android.util.Log, so repository classes are testable
 * under plain JUnit without needing Robolectric just to satisfy Log's Android
 * framework dependency. Production code uses AndroidLogger; tests inject a
 * no-op or recording fake.
 */
interface Logger {
    fun warn(tag: String, message: String)
}

object AndroidLogger : Logger {
    override fun warn(tag: String, message: String) {
        Log.w(tag, message)
    }
}