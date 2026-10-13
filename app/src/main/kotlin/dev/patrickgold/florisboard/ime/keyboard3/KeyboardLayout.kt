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

package dev.patrickgold.florisboard.ime.keyboard3

/**
 * Character layouts selectable in settings and loadable by the keyboard3
 * foundation loader. Each entry maps to a layout asset file inside the
 * org.florisboard.k3.foundation extension.
 */
enum class KeyboardLayout {
    QWERTY,
    QWERTZ;

    /** Path of the layout asset, relative to the k3.foundation extension root. */
    val assetPath: String
        get() = when (this) {
            QWERTY -> "keyboard/qwerty.xml"
            QWERTZ -> "keyboard/qwertz.xml"
        }

    companion object {
        /** Layout used when no user preference has been set yet. */
        val DEFAULT = QWERTY
    }
}
