<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { HugeiconsIcon } from '@hugeicons/vue'
import { Folder01Icon, ArrowLeft01Icon, CookieIcon, Wifi01Icon, BoltIcon } from '@hugeicons/core-free-icons'
import { YtDlp } from '../services/ytdlp'
import { Downswing } from '../services/downswing'
import { useSettingsStore } from '../stores/settings'
import { useDownloadsStore } from '../stores/downloads'
import { formatBytes, parseSize } from '../lib/format'
import { QUALITIES, FOLDERS } from '../lib/options'
import { PALETTES } from '../lib/themes'
import { showToast } from '../lib/toast'
import Switch from '../components/Switch.vue'
import Stepper from '../components/Stepper.vue'
import FolderAccess from '../components/FolderAccess.vue'

const router = useRouter()
const engine = ref('Checking')
const updating = ref(false)
const bio = ref(null) // { supported, enrolled } — guards the app-lock switch

onMounted(async () => {
  try {
    const { version } = await YtDlp.getEngineVersion()
    engine.value = version || 'Bundled build (Nov 2025)'
  } catch {
    engine.value = 'Android app only'
  }
  try {
    bio.value = await Downswing.canBiometric()
  } catch {
    bio.value = { supported: false, enrolled: false }
  }
})

async function updateEngine() {
  updating.value = true
  try {
    const res = await YtDlp.updateEngine()
    engine.value = res.version || engine.value
    showToast(res.status === 'DONE' ? 'yt-dlp updated' : 'yt-dlp is already up to date')
  } catch (e) {
    showToast(`Update failed: ${(e?.message || e).toString().slice(0, 140)}`, 6000)
  } finally {
    updating.value = false
  }
}

const store = useSettingsStore()
const s = store.s

// Lifetime stats, computed from the real list.
const downloadsStore = useDownloadsStore()
const dlStats = computed(() => {
  const list = downloadsStore.items
  const finished = list.filter((i) => i.status === 'completed')
  const failed = list.filter((i) => i.status === 'error')
  const bytes = finished.reduce((sum, i) => sum + parseSize(i.totalText), 0)
  const decided = finished.length + failed.length
  return {
    total: list.length,
    finished: finished.length,
    failed: failed.length,
    bytes,
    rate: decided ? Math.round((finished.length / decided) * 100) : null,
  }
})

// Group palettes for the picker: base first, then dual, then triple.
const paletteGroups = computed(() => [
  { label: 'Base', items: PALETTES.filter((p) => p.group === 'base') },
  { label: 'Dual color', items: PALETTES.filter((p) => p.group === 'dual') },
  { label: 'Triple color', items: PALETTES.filter((p) => p.group === 'triple') },
  { label: 'Pastel', items: PALETTES.filter((p) => p.group === 'pastel') },
].filter((g) => g.items.length))
const THEMES = [['dark', 'Dark'], ['light', 'Light'], ['system', 'System']]

function clearCookie() {
  s.igCookie = ''
  showToast('Instagram cookie cleared')
}

const speed = (kb) => (kb === 0 ? 'No limit' : kb >= 1024 ? `${(kb / 1024).toFixed(kb % 1024 ? 1 : 0)} MB/s` : `${kb} KB/s`)

function reset() {
  if (confirm('Reset all settings to defaults?')) {
    store.reset()
    showToast('Settings reset')
  }
}
</script>

<template>
  <header class="top">
    <button class="icon-btn" aria-label="Back" @click="router.back()"><HugeiconsIcon :icon="ArrowLeft01Icon" :size="24" :stroke-width="2.2" /></button>
    <h1 class="title-sm">Settings</h1>
  </header>

  <h2 class="section-title">Downloads</h2>
  <div class="group">
    <label class="setting stack">
      <span class="s-label"><HugeiconsIcon :icon="Folder01Icon" :size="18" /> Download folder</span>
      <select v-model="s.folder" class="plain">
        <option v-for="f in FOLDERS" :key="f.value" :value="f.value">{{ f.label }}</option>
      </select>
    </label>
    <div class="pad-block"><FolderAccess :folder="s.folder" /></div>
    <p class="hint pad">The app folder lives under Android/data and is hard to browse. Pick Download or another shared folder to see files in your file manager.</p>
    <div class="setting"><span>Default quality</span>
      <select v-model.number="s.quality" class="plain right">
        <option v-for="q in QUALITIES" :key="q" :value="q">{{ q }}p</option>
      </select>
    </div>
    <div class="setting"><span>Max simultaneous downloads</span><Stepper v-model="s.maxSimultaneous" :min="1" :max="6" /></div>
    <div class="setting"><span>Max download speed</span><Stepper v-model="s.maxSpeedKb" :min="0" :max="20480" :step="512" :format="speed" /></div>
    <div class="setting"><span>Start downloads automatically</span><Switch v-model="s.startAuto" label="Start downloads automatically" /></div>
    <div class="setting"><span>Audio downloads go to Music</span><Switch v-model="s.smartAudioFolder" label="Audio downloads go to Music" /></div>
  </div>

  <h2 class="section-title">Network</h2>
  <div class="group">
    <div class="setting"><span class="s-label"><HugeiconsIcon :icon="Wifi01Icon" :size="17" /> Wi-Fi only</span><Switch v-model="s.wifiOnly" label="Wi-Fi only" /></div>
    <p v-if="s.wifiOnly" class="hint pad">Queued downloads wait until the phone is on Wi-Fi; they fail fast on mobile data instead of burning it.</p>
    <div class="setting"><span class="s-label"><HugeiconsIcon :icon="BoltIcon" :size="17" /> Only download while charging</span><Switch v-model="s.chargingOnly" label="Only download while charging" /></div>
    <p v-if="s.chargingOnly" class="hint pad">Queued downloads wait until the charger is connected.</p>
  </div>

  <h2 class="section-title">Instagram</h2>
  <div class="group">
    <label class="setting stack" for="igcookie">
      <span class="s-label"><HugeiconsIcon :icon="CookieIcon" :size="17" /> Cookie for logged-in posts</span>
      <input id="igcookie" v-model="s.igCookie" class="text-in" type="text" spellcheck="false" autocomplete="off"
        autocapitalize="off" placeholder="sessionid=…; csrftoken=…; ds_user_id=…">
    </label>
    <p class="hint pad">Private, age-restricted or region-locked Instagram posts only download with your session cookie. In a browser: open the post, DevTools → Network → any request → Request Headers → copy the <b>Cookie</b> value. It stays on this device and is sent only to instagram.com.</p>
    <div v-if="s.igCookie" class="setting">
      <span class="muted small">Cookie saved · {{ s.igCookie.length }} chars</span>
      <button class="text-btn" @click="clearCookie">Clear</button>
    </div>
  </div>

  <h2 class="section-title">Appearance</h2>
  <div class="group">
    <div class="setting">
      <span>Theme</span>
      <div class="seg three" role="radiogroup" aria-label="Theme">
        <button v-for="[v, label] in THEMES" :key="v" role="radio" :aria-checked="s.theme === v"
          :class="{ on: s.theme === v }" @click="s.theme = v">{{ label }}</button>
      </div>
    </div>
  </div>

  <div v-for="g in paletteGroups" :key="g.label" class="pal-block">
    <span class="tape pal-tape">{{ g.label }}</span>
    <div class="pal-grid" role="radiogroup" :aria-label="`${g.label} colour themes`">
      <button v-for="p in g.items" :key="p.id" class="pal" :class="{ on: s.palette === p.id }"
        role="radio" :aria-checked="s.palette === p.id" @click="s.palette = p.id">
        <span class="pal-dots">
          <i :style="{ background: p.primary }" />
          <i v-if="p.secondary" :style="{ background: p.secondary }" />
          <i :style="{ background: p.accent }" />
        </span>
        <span class="pal-name">{{ p.name }}</span>
      </button>
    </div>
  </div>

  <p class="hint">Colour theme changes the buttons, active tabs and badges — the paper, ink and pastels stay the same.</p>

  <h2 class="section-title">Your downloads</h2>
  <div class="group">
    <template v-if="dlStats.total">
      <div class="setting"><span>Finished</span><span class="muted num">{{ dlStats.finished }} of {{ dlStats.total }}</span></div>
      <div class="setting"><span>Data finished</span><span class="muted num">{{ formatBytes(dlStats.bytes) }}</span></div>
      <div v-if="dlStats.rate !== null" class="setting"><span>Success rate</span><span class="muted num">{{ dlStats.rate }}%</span></div>
      <div v-if="dlStats.failed" class="setting"><span>Failed</span><span class="bad num">{{ dlStats.failed }}</span></div>
    </template>
    <div v-else class="setting"><span class="muted">No downloads yet</span></div>
  </div>

  <h2 class="section-title">Notifications</h2>
  <div class="group">
    <div class="setting"><span>Download complete</span><Switch v-model="s.notifyComplete" label="Notify when complete" /></div>
    <div class="setting"><span>Download failed</span><Switch v-model="s.notifyFail" label="Notify when failed" /></div>
    <div class="setting"><span>Vibrate when complete</span><Switch v-model="s.vibrateOnFinish" label="Vibrate when complete" /></div>
  </div>

  <h2 class="section-title">Security</h2>
  <div class="group">
    <div class="setting"><span>Lock app with biometrics</span><Switch v-model="s.appLock" :disabled="!!bio && !bio.enrolled" label="Lock app with biometrics" /></div>
    <p v-if="bio && !bio.enrolled" class="hint pad">{{ bio.supported ? 'Set up a fingerprint or face unlock on this device first.' : 'This device has no fingerprint or face unlock.' }}</p>
    <p v-else class="hint pad">Downswing asks for your fingerprint, face or screen lock when it opens.</p>
  </div>

  <h2 class="section-title">Engine</h2>
  <div class="group">
    <div class="setting"><span>yt-dlp version</span><span class="muted num eng">{{ engine }}</span></div>
    <div class="setting"><span>Update yt-dlp</span><button class="text-btn" :disabled="updating" @click="updateEngine">{{ updating ? 'Updating' : 'Update now' }}</button></div>
  </div>
  <p class="hint">Sites change often and an old yt-dlp stops working. Downswing also updates it on launch when you are online.</p>

  <div class="actions"><button class="btn ghost" @click="reset">Reset settings</button></div>
</template>
