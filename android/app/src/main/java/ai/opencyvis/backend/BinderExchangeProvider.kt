package ai.opencyvis.backend

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ContentProvider that receives IBinder from the PrivilegedService process.
 * The privileged process calls ContentResolver.call() with a Bundle
 * containing the Binder + a one-time token for authentication.
 */
class BinderExchangeProvider : ContentProvider() {
    companion object {
        private const val TAG = "BinderExchange"

        @Volatile
        private var pendingToken: String? = null
        @Volatile
        private var receivedBinder: IBinder? = null
        private var latch = CountDownLatch(1)

        fun prepare(): String {
            val token = java.util.UUID.randomUUID().toString()
            pendingToken = token
            receivedBinder = null
            latch = CountDownLatch(1)
            return token
        }

        fun awaitBinder(timeoutMs: Long): IBinder? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val binder = receivedBinder
            receivedBinder = null
            pendingToken = null
            return binder
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Only accept binder exchanges from shell (uid 2000), root (0), or system (1000)
        val callerUid = android.os.Binder.getCallingUid()
        if (callerUid != 2000 && callerUid != 0 && callerUid != 1000) {
            Log.w(TAG, "Rejected binder exchange from uid=$callerUid")
            return null
        }

        if (method == "exchangeBinder") {
            val token = extras?.getString("token")
            if (token != null && token == pendingToken) {
                receivedBinder = extras.getBinder("binder")
                Log.i(TAG, "Binder received (token matched)")
                latch.countDown()
            } else {
                Log.w(TAG, "Binder exchange rejected: token mismatch")
            }
        }
        return null
    }

    // Required ContentProvider overrides (unused)
    override fun query(
        uri: Uri,
        proj: Array<String>?,
        sel: String?,
        selArgs: Array<String>?,
        order: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, selArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        sel: String?,
        selArgs: Array<String>?
    ): Int = 0
}
