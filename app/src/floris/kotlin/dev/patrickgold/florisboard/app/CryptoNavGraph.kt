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

/** Whether the CryptoBoard settings entry is part of this build. */
const val CryptoSettingsAvailable = false

/**
 * Registers the CryptoBoard routes. The floris flavor does not ship CryptoBoard,
 * so this is intentionally a no-op (and [CryptoSettingsAvailable] keeps the
 * settings entry out of the UI to avoid navigating to an unregistered route).
 */
fun NavGraphBuilder.addCryptoRoutes() {
}
