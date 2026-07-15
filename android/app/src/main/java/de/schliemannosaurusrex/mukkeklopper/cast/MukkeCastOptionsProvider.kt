package de.schliemannosaurusrex.mukkeklopper.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Registriert über `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME` im
 * Manifest (PLAN „Debug-Log, Sync-Fix, Queue, Cast" Punkt 4). Nutzt den generischen
 * Default-Media-Receiver — kein eigener Cast-Receiver nötig, um Audio zu streamen.
 */
class MukkeCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
