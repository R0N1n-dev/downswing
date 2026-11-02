import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'
import { StatusBar, Style as StatusBarStyle } from '@capacitor/status-bar'
import { QUALITIES, DEFAULT_FOLDER } from '../lib/options'
import { PALETTE_IDS } from '../lib/themes'

const KEY = 'downly.settings'

const defaults = () => ({
  // Download root. '' means the private app folder; anything else is a shared device folder.
  folder: DEFAULT_FOLDER,
  // Split that root into Downswing/<Category> — see lib/categories.js.
  categoryFolders: true,
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
    // Installs from before category folders defaulted to the private app folder, which a file
    // manager cannot reach. Move them onto the new layout — Downloads/Downswing/<Category> —
    // unless they had picked a folder of their own. One-time: the flag is saved from now on.
    if (saved.categoryFolders === undefined) {
      merged.categoryFolders = true
      if (!saved.folder) merged.folder = DEFAULT_FOLDER
      // Write it back now: the change should survive this session even if nothing else is edited.
      try { localStorage.setItem(KEY, JSON.stringify(merged)) } catch { /* storage full */ }
    }
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
