package com.grinwich.sdk.wiring.k

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import com.grinwich.sdk.contracts.FeatureProvider

/**
 * Dummy Service — exists only so feature-impl modules can attach
 * `<meta-data>` entries to it in their own AndroidManifest.xml.
 * Never started or bound.
 */
class ComponentDiscoveryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

private const val META_PREFIX = "com.grinwich.sdk.providers:"

/**
 * Firebase-style discovery: reads `<meta-data>` from [ComponentDiscoveryService]
 * across all merged manifests and instantiates [FeatureProvider] classes.
 */
object ComponentDiscovery {

    fun discover(context: Context): List<FeatureProvider<*>> {
        val component = ComponentName(context, ComponentDiscoveryService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(
            component,
            PackageManager.GET_META_DATA,
        )
        val bundle = serviceInfo.metaData ?: return emptyList()

        return bundle.keySet()
            .filter { it.startsWith(META_PREFIX) }
            .map { key ->
                val className = key.removePrefix(META_PREFIX)
                Class.forName(className)
                    .asSubclass(FeatureProvider::class.java)
                    .getDeclaredConstructor()
                    .newInstance()
            }
    }
}
