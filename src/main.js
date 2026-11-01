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

// Boot sticker (see index.html): the launch sequence, in one place.
//
// The app's first render is not at rest — the empty state, the rows and the hero each slide in
// 6px over 0.18s (see `row-in` in style.css). Suppressing those animations and re-enabling them
// later only postpones the slide to the moment the sticker leaves, which is exactly the jitter
// this is here to hide. So the sticker waits for them instead: it lifts once the webfonts are in
// and every finite animation from that first render has finished, then fades over 0.18s.
function firstRenderSettled() {
  // Only CSS animations that end: the live ring's dashed spinner runs forever and must not hold us.
  const running = () => document.getAnimations().filter((a) =>
    a.constructor.name === 'CSSAnimation' && a.effect?.getTiming().iterations !== Infinity && a.playState === 'running')
  return new Promise((resolve) => {
    // One frame after mount: by now the first render's animations exist to be waited on.
    setTimeout(() => {
      const list = running()
      if (!list.length) return resolve()
      let left = list.length
      const done = () => { if (--left === 0) resolve() }
      list.forEach((a) => { a.finished.then(done, done) }) // cancelled counts as finished
    }, 32)
  })
}

const boot = document.getElementById('boot')
if (boot) {
  const readable = new Promise((resolve) => setTimeout(resolve, 280)) // a logo, not a flicker
  const fonts = Promise.race([document.fonts?.ready ?? Promise.resolve(), new Promise((resolve) => setTimeout(resolve, 700))])
  const settled = Promise.race([firstRenderSettled(), new Promise((resolve) => setTimeout(resolve, 600))])
  Promise.all([readable, fonts, settled]).then(() => {
    // A timer, not requestAnimationFrame: rAF stalls whenever frames are not being produced.
    setTimeout(() => {
      boot.classList.add('done')
      setTimeout(() => boot.remove(), 200)
    }, 30)
  })
}

