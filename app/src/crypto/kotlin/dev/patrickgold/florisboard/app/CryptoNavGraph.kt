/*
 * Copyright (C) 2026 MK
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

package dev.patrickgold.florisboard.app

import androidx.navigation.NavGraphBuilder
import com.mkcreations.cryptokeyboard.ui.CryptoSettingsScreen
import com.mkcreations.cryptokeyboard.ui.KeyManagementScreen

/** Whether the CryptoBoard settings entry is part of this build. */
const val CryptoSettingsAvailable = true

/** Registers the CryptoBoard routes. Only called by [Routes.AppNavHost]. */
fun NavGraphBuilder.addCryptoRoutes() {
    composableWithDeepLink(Routes.Settings.Crypto::class) { CryptoSettingsScreen() }
    composableWithDeepLink(Routes.Settings.KeyManagement::class) { KeyManagementScreen() }
}
