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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyboardLayoutTest : FunSpec({
    context("KeyboardLayout.assetPath") {
        test("QWERTY maps to the qwerty asset file") {
            KeyboardLayout.QWERTY.assetPath shouldBe "keyboard/qwerty.xml"
        }

        test("QWERTZ maps to the qwertz asset file") {
            KeyboardLayout.QWERTZ.assetPath shouldBe "keyboard/qwertz.xml"
        }
    }

    context("KeyboardLayout.DEFAULT") {
        test("the default layout is QWERTY") {
            KeyboardLayout.DEFAULT shouldBe KeyboardLayout.QWERTY
        }

        test("the default layout's asset file exists in the k3.foundation extension") {
            val candidates = listOf(
                "src/main/assets/extensions/org.florisboard.k3.foundation",
                "app/src/main/assets/extensions/org.florisboard.k3.foundation",
            )
            val assetsRoot = candidates.map { java.io.File(it) }.firstOrNull(java.io.File::isDirectory)
                ?: error("k3.foundation assets not found (working dir: ${java.io.File("").absolutePath})")
            java.io.File(assetsRoot, KeyboardLayout.DEFAULT.assetPath).isFile shouldBe true
        }
    }
})
