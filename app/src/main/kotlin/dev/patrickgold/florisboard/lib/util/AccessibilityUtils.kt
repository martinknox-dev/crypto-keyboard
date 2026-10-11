/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.lib.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Flavor-agnostic helpers for the accessibility (screen-scanning decoder)
 * service. Works purely on package/state introspection, so the shared
 * (main) source set never references flavor-specific classes — the crypto
 * flavor declares an accessibility service, the floris flavor does not.
 */
object AccessibilityUtils {
    private const val BIND_ACCESSIBILITY_SERVICE = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private const val OBSERVE_INTERVAL_MS = 500L

    /**
     * Returns true if this package declares a service protected by
     * [BIND_ACCESSIBILITY_SERVICE]. Used to show/hide the onboarding step.
     */
    fun isServiceDeclared(context: Context): Boolean {
        return resolveDeclaredService(context) != null
    }

    /**
     * The label of the declared accessibility service exactly as it appears
     * in the system's Accessibility settings list, or null when the package
     * declares no such service. Falls back to the class name if the manifest
     * provides no readable label.
     */
    fun resolveDeclaredServiceLabel(context: Context): String? {
        val service = resolveDeclaredService(context) ?: return null
        val label = service.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
        return label ?: service.name.substringAfterLast('.')
    }

    /**
     * Returns true if any accessibility service belonging to our package is
     * currently enabled in system settings.
     */
    fun isServiceEnabled(context: Context): Boolean {
        return try {
            val flatList = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            flatList.split(':').any { entry ->
                ComponentName.unflattenFromString(entry)?.packageName == context.packageName
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Observe the enabled state of our accessibility service. Polls instead
     * of relying on content observer notifications: Android 14+ no longer
     * reliably notifies for some Secure settings keys (same reason
     * InputMethodUtils uses timed queries there), so polling gives
     * consistent behavior across all versions. Stops flipping once the
     * service is enabled.
     */
    @Composable
    fun observeServiceEnabled(
        context: Context = LocalContext.current.applicationContext,
        isDeclared: Boolean = true,
    ): State<Boolean> {
        if (!isDeclared) return mutableStateOf(false)
        return produceState(initialValue = isServiceEnabled(context)) {
            while (true) {
                delay(OBSERVE_INTERVAL_MS)
                val enabled = isServiceEnabled(context)
                if (enabled != value) {
                    value = enabled
                }
            }
        }
    }

    private fun resolveDeclaredService(context: Context): ServiceInfo? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SERVICES,
            )
            packageInfo.services?.firstOrNull { it.permission == BIND_ACCESSIBILITY_SERVICE }
        } catch (e: Exception) {
            null
        }
    }
}
