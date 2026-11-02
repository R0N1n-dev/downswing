import { ref } from 'vue'

// Proof, on the device, that the app opened in place.
//
// The launch "shake" was the WebView being re-measured after its first paint — native insets and
// window chrome arriving a few frames late. None of that is reproducible in a desktop browser, so
// the app now measures its own launch: it samples the frame for the first 1.5 s, remembers the
// first value it saw, and records any field that moved afterwards. Settings → Engine shows the
// result, so a report can be read off the phone instead of guessed at.
const KEY = 'downswing.launch'

const FIELDS = {
  h: 'window height',
  vh: 'visual height',
  top: 'viewport offset',
  sh: 'screen height',
  sat: 'top inset',
}

export const launchReport = ref(read())

function read() {
  try { return JSON.parse(localStorage.getItem(KEY) || 'null') } catch { return null }
}

function sample() {
  const vv = window.visualViewport
  const rect = document.querySelector('.shell')?.getBoundingClientRect()
  return {
    h: Math.round(window.innerHeight),
    vh: vv ? Math.round(vv.height) : 0,
    top: vv ? Math.round(vv.offsetTop) : 0,
    sh: rect ? Math.round(rect.height) : 0,
    sat: getComputedStyle(document.documentElement).getPropertyValue('--sat').trim(),
  }
}

export function watchLaunch({ ms = 1500, every = 120 } = {}) {
  if (typeof window === 'undefined' || !window.visualViewport) return
  // Baseline after two frames: the first render has landed, so nothing counts as "moved" before
  // the app has drawn at all.
  requestAnimationFrame(() => requestAnimationFrame(() => {
    const first = sample()
    const moved = new Map()
    const tick = setInterval(() => {
      const now = sample()
      for (const k of Object.keys(FIELDS)) {
        if (String(now[k]) !== String(first[k]) && !moved.has(k)) {
          moved.set(k, { field: FIELDS[k], from: first[k], to: now[k], at: Math.round(performance.now()) })
        }
      }
    }, every)
    setTimeout(() => {
      clearInterval(tick)
      const report = { checkedAt: Date.now(), stable: moved.size === 0, first, shifts: [...moved.values()] }
      launchReport.value = report
      try { localStorage.setItem(KEY, JSON.stringify(report)) } catch { /* storage full */ }
    }, ms)
  }))
}

// One line for Settings: "Stable" or what moved and when.
export function launchSummary(report) {
  if (!report) return 'Not measured yet'
  if (report.stable) return 'Stable — nothing moved while the app opened'
  return report.shifts.map((s) => `${s.field} ${s.from}→${s.to} at ${s.at} ms`).join(' · ')
}
