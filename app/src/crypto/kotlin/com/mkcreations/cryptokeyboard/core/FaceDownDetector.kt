package com.mkcreations.cryptokeyboard.core

/**
 * Fires when the device is flipped face-down: the transition into the
 * face-down tilt state while the proximity sensor is covered (or the
 * proximity cover arriving while already tilted — order independent).
 *
 * Edge-triggered so a device lying still does not re-fire: after a fire the
 * detector re-arms only once BOTH states fully release (tilt up + proximity
 * clear), and a cooldown absorbs proximity flicker at the sensor boundary.
 */
class FaceDownDetector(
    private val cooldownMs: Long = 5_000L,
) {
    private var faceDown = false
    private var near = false
    private var armed = true
    private var lastFiredAt: Long? = null

    /** Records tilt state; true on the transition into face-down while covered. */
    fun onTilt(faceDownNow: Boolean, nowMs: Long): Boolean {
        val enteredFaceDown = faceDownNow && !faceDown
        faceDown = faceDownNow
        rearmIfReleased()
        return enteredFaceDown && near && canFire(nowMs)
    }

    /** Records proximity state; true on the transition to near while tilted. */
    fun onProximityNear(nearNow: Boolean, nowMs: Long): Boolean {
        val becameNear = nearNow && !near
        near = nearNow
        rearmIfReleased()
        return becameNear && faceDown && canFire(nowMs)
    }

    /** Both states must fully release before the detector can fire again. */
    private fun rearmIfReleased() {
        if (!faceDown && !near) armed = true
    }

    private fun canFire(nowMs: Long): Boolean {
        if (!armed) return false
        lastFiredAt?.let { firedAt -> if (nowMs - firedAt < cooldownMs) return false }
        armed = false
        lastFiredAt = nowMs
        return true
    }
}
