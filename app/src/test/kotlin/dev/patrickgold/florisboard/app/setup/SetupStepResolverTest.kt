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

package dev.patrickgold.florisboard.app.setup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SetupStepResolverTest : FunSpec({
    fun resolve(
        imeEnabled: Boolean = true,
        imeSelected: Boolean = true,
        a11yDeclared: Boolean = true,
        a11yEnabled: Boolean = false,
        a11ySkipped: Boolean = false,
        notification: NotificationPermissionState = NotificationPermissionState.GRANTED,
        isApi33OrAbove: Boolean = false,
    ) = resolveSetupStep(
        isImeEnabled = imeEnabled,
        isImeSelected = imeSelected,
        hasAccessibilityService = a11yDeclared,
        isAccessibilityEnabled = a11yEnabled,
        isAccessibilitySkipped = a11ySkipped,
        notificationPermission = notification,
        isApi33OrAbove = isApi33OrAbove,
    )

    context("resolveSetupStep") {
        test("starts at EnableIme when the keyboard is not enabled") {
            resolve(imeEnabled = false) shouldBe Steps.EnableIme
        }

        test("moves to SelectIme once the keyboard is enabled but not selected") {
            resolve(imeSelected = false) shouldBe Steps.SelectIme
        }

        test("considers the keyboard steps before the accessibility step") {
            resolve(imeSelected = false, a11yEnabled = false) shouldBe Steps.SelectIme
        }

        test("shows the accessibility step after SelectIme while service is not enabled") {
            resolve() shouldBe Steps.EnableAccessibility
        }

        test("advances past the accessibility step when the service is enabled") {
            resolve(a11yEnabled = true) shouldBe Steps.FinishUp
        }

        test("advances past the accessibility step when the user chose maybe later") {
            resolve(a11ySkipped = true) shouldBe Steps.FinishUp
        }

        test("omits the accessibility step when the package declares no service") {
            resolve(a11yDeclared = false) shouldBe Steps.FinishUp
        }

        test("asks for notification permission on android 13+ when not set") {
            resolve(
                a11yEnabled = true,
                notification = NotificationPermissionState.NOT_SET,
                isApi33OrAbove = true,
            ) shouldBe Steps.SelectNotification
        }

        test("after accessibility, the notification step comes next on android 13+") {
            resolve(
                a11ySkipped = true,
                notification = NotificationPermissionState.NOT_SET,
                isApi33OrAbove = true,
            ) shouldBe Steps.SelectNotification
        }

        test("does not ask for notification permission below android 13") {
            resolve(
                a11yEnabled = true,
                notification = NotificationPermissionState.NOT_SET,
                isApi33OrAbove = false,
            ) shouldBe Steps.FinishUp
        }

        test("a denied notification permission does not block finish") {
            resolve(
                a11yEnabled = true,
                notification = NotificationPermissionState.DENIED,
                isApi33OrAbove = true,
            ) shouldBe Steps.FinishUp
        }
    }

    context("Steps ids") {
        test("are strictly increasing in display order") {
            listOf(
                Steps.EnableIme,
                Steps.SelectIme,
                Steps.EnableAccessibility,
                Steps.SelectNotification,
                Steps.FinishUp,
            ).map { it.id } shouldBe listOf(1, 2, 3, 4, 5)
        }
    }
})
