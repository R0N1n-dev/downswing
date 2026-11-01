import { ref } from 'vue'
import { Downswing } from '../services/downswing'

export const locked = ref(false)
export const lockError = ref('')

// Arm the lock screen if the user enabled it AND the device can actually unlock.
export async function checkLock(enabled) {
  if (!enabled) return false
  try {
    const { enrolled } = await Downswing.canBiometric()
    if (enrolled) {
      lockError.value = ''
      locked.value = true
      return true
    }
  } catch { /* browser preview: no biometrics */ }
  return false
}

// Ask Android for fingerprint / face / screen lock. Never throws.
export async function unlock() {
  try {
    const res = await Downswing.authenticate()
    if (res.ok) {
      locked.value = false
      lockError.value = ''
      return true
    }
    // BiometricPrompt codes: 7/8 lockout, 9 cancelled by the user.
    lockError.value = res.code === 7 || res.code === 8
      ? 'Too many attempts. Wait a moment and try again.'
      : res.code === 9
        ? ''
        : 'Could not verify you. Try again.'
  } catch {
    lockError.value = 'Biometrics are not available right now.'
  }
  return false
}
