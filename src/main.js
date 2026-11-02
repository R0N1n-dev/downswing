import '@fontsource/archivo-black'
import '@fontsource/space-grotesk/400.css'
import '@fontsource/space-grotesk/500.css'
import '@fontsource/space-grotesk/700.css'
import './style.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useSettingsStore } from './stores/settings'

const app = createApp(App)
app.use(createPinia())
app.use(router)
useSettingsStore().applyTheme()
app.mount('#app')

// Launch: hold the sticker (see index.html) until the first render is actually at rest.
//
// The window used to open on a screen that was still finding its size — the viewport settling,
// the webfonts arriving and the list laying itself out all landed after the splash, which read as
// the whole screen shaking. Now the sticker covers exactly that moment and lifts on a fade, so
// the app appears already in place.
const boot = document.getElementById('boot')
if (boot) {
  // Two frames: one to commit the first render, one to let it lay out.
  const painted = new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
  // Fonts change the height of every title; wait for them, but never hold the launch on them.
  const fonts = Promise.race([
    document.fonts?.ready ?? Promise.resolve(),
    new Promise((resolve) => setTimeout(resolve, 700)),
  ])
  // A logo, not a flicker.
  const readable = new Promise((resolve) => setTimeout(resolve, 200))
  Promise.race([
    Promise.all([painted, fonts, readable]),
    new Promise((resolve) => setTimeout(resolve, 900)), // a stalled WebView never keeps it up
  ]).then(() => {
    boot.classList.add('done')
    setTimeout(() => boot.remove(), 220)
  })
}

