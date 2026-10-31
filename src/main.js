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

// Boot sticker (see index.html). Hold it ~0.28s so it reads as a logo rather than a flicker, but
// never past the fonts, so the first thing revealed is already in Archivo Black and Space Grotesk
// instead of swapping type mid-animation. Then fade out over the 0.18s motion budget.
const boot = document.getElementById('boot')
if (boot) {
  const fonts = document.fonts?.ready ?? Promise.resolve()
  const painted = new Promise((resolve) => setTimeout(resolve, 280))
  const fontsReady = Promise.race([fonts, new Promise((resolve) => setTimeout(resolve, 700))])
  Promise.all([painted, fontsReady]).then(() => {
    // A timer, not requestAnimationFrame: the app is already mounted under the sticker and the
    // hold above guarantees it has painted, while rAF stalls whenever frames are not being produced.
    setTimeout(() => {
      boot.classList.add('done')
      setTimeout(() => {
        boot.remove()
        // Entrance animations are allowed again once the sticker is gone: nothing moves behind it.
        document.documentElement.classList.remove('booting')
      }, 200)
    }, 30)
  })
}

