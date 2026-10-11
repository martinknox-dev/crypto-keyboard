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

/**
 * Onboarding step identifiers.
 *
 * Ids must be strictly increasing in display order: [FlorisStepLayout] uses
 * the id ordering to decide which step headers are unlocked/clickable.
 * (FlorisStepLayout lives in lib/compose; referenced here by name only.)
 */
internal sealed class Steps(val id: Int) {
    data object EnableIme : Steps(id = 1)
    data object SelectIme : Steps(id = 2)
    data object EnableAccessibility : Steps(id = 3)
    data object SelectNotification : Steps(id = 4)
    data object FinishUp : Steps(id = 5)
}

/**
 * Resolves which onboarding step should currently be active based on the
 * completion state of every prerequisite. Pure function — unit tested in
 * [SetupStepResolverTest].
 */
internal fun resolveSetupStep(
    isImeEnabled: Boolean,
    isImeSelected: Boolean,
    hasAccessibilityService: Boolean,
    isAccessibilityEnabled: Boolean,
    isAccessibilitySkipped: Boolean,
    notificationPermission: NotificationPermissionState,
    isApi33OrAbove: Boolean,
): Steps = when {
    // Keyboard prerequisites first: the user must establish trust by
    // enabling and selecting the keyboard before we ask for accessibility.
    !isImeEnabled -> Steps.EnableIme
    !isImeSelected -> Steps.SelectIme
    // Screen-scanning decoder (crypto flavor only — step is omitted when the
    // package declares no accessibility service). Passes once the user
    // enables the service live, or persists "Maybe later" as skipped.
    hasAccessibilityService && !isAccessibilityEnabled && !isAccessibilitySkipped -> Steps.EnableAccessibility
    isApi33OrAbove && notificationPermission == NotificationPermissionState.NOT_SET -> Steps.SelectNotification
    else -> Steps.FinishUp
}
