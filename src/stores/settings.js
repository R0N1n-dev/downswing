import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'
import { StatusBar, Style as StatusBarStyle } from '@capacitor/status-bar'
import { QUALITIES } from '../lib/options'
import { PALETTE_IDS } from '../lib/themes'

// Same pre-rename namespace as the queue (see stores/downloads.js): the Instagram cookie, folder
// and theme survive the rename.
const KEY = 'downly.settings'

const defaults = () => ({
  folder: '',
  maxSimultaneous: 3,
  maxSpeedKb: 0,
  quality: 720,
  startAuto: true,
  wifiOnly: false,
  smartAudioFolder: true,
  theme: 'dark',
  palette: 'default',
  notifyComplete: true,
  notifyFail: true,
  vibrateOnFinish: true,
  chargingOnly: false,
  appLock: false,
  igCookie: '', // raw Cookie header for Instagram posts that need a logged-in session
})

function load() {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) || '{}')
    const merged = { ...defaults(), ...saved }
    if (!QUALITIES.includes(merged.quality)) merged.quality = 720
    if (!PALETTE_IDS.includes(merged.palette)) merged.palette = 'default'
    return merged
  } catch {
    return defaults()
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const s = reactive(load())
  const media = window.matchMedia?.('(prefers-color-scheme: dark)')

  // The native status bar must follow the app theme, otherwise the icons stay dark on a dark app.
  function applyStatusBar(dark) {
    try {
      StatusBar.setStyle({ style: dark ? StatusBarStyle.Dark : StatusBarStyle.Light }).catch(() => {})
      // --bg can be a color-mix() expression, so read the resolved color off the body instead.
      const resolved = getComputedStyle(document.body).backgroundColor
      const m = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(resolved)
      if (m) {
        const hex = `#${m.slice(1, 4).map((n) => (+n).toString(16).padStart(2, '0')).join('')}`
        StatusBar.setBackgroundColor({ color: hex }).catch(() => {})
      }
    } catch { /* browser preview */ }
  }

  function applyTheme() {
    const dark = s.theme === 'system' ? (media ? media.matches : true) : s.theme === 'dark'
    const root = document.documentElement
    root.setAttribute('data-theme', dark ? 'dark' : 'light')
    root.setAttribute('data-palette', s.palette)
    applyStatusBar(dark)
  }

  watch(s, () => {
    try { localStorage.setItem(KEY, JSON.stringify(s)) } catch { /* storage full */ }
    applyTheme()
  }, { deep: true })
  media?.addEventListener?.('change', applyTheme)
  applyTheme()

  function reset() {
    Object.assign(s, defaults())
  }

  return { s, reset, applyTheme }
})
