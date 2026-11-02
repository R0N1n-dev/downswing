import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'
import { StatusBar, Style as StatusBarStyle } from '@capacitor/status-bar'
import { Downswing } from '../services/downswing'
import { QUALITIES, DEFAULT_FOLDER } from '../lib/options'
import { PALETTE_IDS } from '../lib/themes'

// Same pre-rename namespace as the queue (see stores/downloads.js): the Instagram cookie, folder
// and theme survive the rename.
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

  // Native chrome follows the theme, but only when it actually changes: the status bar icons and
  // the colour of the Android window behind the WebView (the strip native inset handling reserves
  // for the status bar, see capacitor.config.json → SystemBars.insetsHandling).
  //
  // Deliberately nothing here touches layout. Re-applying window chrome on every boot, or resizing
  // anything from the native inset values, is what used to make the whole screen move a few frames
  // into launch. setBackgroundColor is gone too: it has no effect on Android 15+, where the window
  // colour is what the strip shows.
  let barStyle = null
  let windowColor = null
  function applyChrome(dark) {
    const style = dark ? StatusBarStyle.Dark : StatusBarStyle.Light
    if (style !== barStyle) {
      barStyle = style
      try { StatusBar.setStyle({ style }).catch(() => {}) } catch { /* browser preview */ }
    }
    // --bg can be a color-mix() expression, so read the resolved colour off the body instead.
    const m = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(getComputedStyle(document.body).backgroundColor)
    if (!m) return
    const hex = `#${m.slice(1, 4).map((n) => (+n).toString(16).padStart(2, '0')).join('')}`
    if (hex === windowColor) return
    windowColor = hex
    try { Downswing.setWindowBackground({ color: hex }).catch(() => {}) } catch { /* browser preview */ }
  }

  function applyTheme() {
    const dark = s.theme === 'system' ? (media ? media.matches : true) : s.theme === 'dark'
    const root = document.documentElement
    root.setAttribute('data-theme', dark ? 'dark' : 'light')
    root.setAttribute('data-palette', s.palette)
    applyChrome(dark)
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
