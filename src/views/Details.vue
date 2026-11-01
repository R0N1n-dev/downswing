<script setup>
import { computed, ref, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HugeiconsIcon } from '@hugeicons/vue'
import { ArrowLeft01Icon, Edit01Icon, CheckIcon, Copy01Icon, ShieldCheckIcon, FolderOpenIcon, Share01Icon, GaugeIcon } from '@hugeicons/core-free-icons'
import { Clipboard } from '@capacitor/clipboard'
import { Downswing } from '../services/downswing'
import { showToast } from '../lib/toast'
import { useDownloadsStore } from '../stores/downloads'
import FileTile from '../components/FileTile.vue'
import { formatDate, rangeText } from '../lib/format'
import { KIND_LABEL } from '../lib/detect'

const route = useRoute()
const router = useRouter()
const store = useDownloadsStore()
const item = computed(() => store.byId(route.params.id))

const status = computed(() => ({
  downloading: 'Downloading', retrying: 'Retrying', queued: 'Queued', paused: 'Paused', completed: 'Completed', error: 'Failed',
}[item.value?.status] || ''))

const statusClass = computed(() => ({ completed: 'good', error: 'bad', downloading: 'blue', retrying: 'blue' }[item.value?.status] || 'muted'))
const tileState = computed(() => ({ downloading: 'run', retrying: 'hold', paused: 'hold', error: 'fail', completed: 'done' }[item.value?.status] || ''))
const primaryLabel = computed(() => ({ downloading: 'Pause', retrying: 'Pause', queued: 'Start now', paused: 'Resume', error: 'Retry' }[item.value?.status] || 'Resume'))

async function cancel() {
  if (confirm('Cancel and remove this download? Partial data stays on disk.')) {
    await store.cancel(item.value.id)
    router.replace('/')
  }
}

function primary() {
  const i = item.value
  if (i.status === 'downloading' || i.status === 'retrying') store.pause(i.id)
  else if (i.status === 'queued') store.startNow(i.id)
  else store.resume(i.id)
}

// Inline rename (window.prompt is unavailable in Android WebView).
const renaming = ref(false)
const renameDraft = ref('')
const nameInput = ref(null)
async function startRename() {
  renameDraft.value = item.value.name
  renaming.value = true
  await nextTick()
  nameInput.value?.focus()
  nameInput.value?.select()
}
function saveRename() {
  const v = renameDraft.value.trim()
  if (v) item.value.name = v
  renaming.value = false
  showToast('Renamed')
}

// Drop it from the list but keep the file — the snackbar can put the row back.
function removeFromList() {
  const gone = item.value
  store.remove(gone.id)
  showToast('Removed from the list', 4500, { label: 'Undo', run: () => store.restore([gone]) })
  router.replace('/history')
}

async function copyUrl() {
  try {
    await Clipboard.write({ string: item.value.url })
    showToast('Link copied')
  } catch {
    showToast('Could not copy the link')
  }
}

// Open or hand the finished file to another app through the native FileProvider.
function pathOrToast() {
  const p = item.value?.filePath
  if (!p) {
    showToast('The file is no longer on this device.')
    return null
  }
  return p
}

async function openFile() {
  const path = pathOrToast()
  if (!path) return
  try { await Downswing.openFile({ path }) } catch (e) {
    showToast(/not implemented/i.test(e?.message || '') ? 'Opening files only works in the Android app.' : e?.message || 'No app can open this file.')
  }
}

async function shareFile() {
  const path = pathOrToast()
  if (!path) return
  try { await Downswing.shareFile({ path, text: item.value.url }) } catch (e) {
    showToast(/not implemented/i.test(e?.message || '') ? 'Sharing files only works in the Android app.' : e?.message || 'Could not share that file.')
  }
}

// Per-download speed limit: null follows the global setting, 0 is unlimited.
const LIMITS = [
  ['global', 'Global setting'],
  ['0', 'Unlimited'],
  ['512', '512 KB/s'],
  ['1024', '1 MB/s'],
  ['2048', '2 MB/s'],
  ['5120', '5 MB/s'],
]
const limitValue = computed(() => (item.value?.limitKb == null ? 'global' : String(item.value.limitKb)))
function setLimit(ev) {
  const v = ev.target.value
  item.value.limitKb = v === 'global' ? null : Number(v)
  const label = LIMITS.find(([k]) => k === v)?.[1] || 'Global setting'
  showToast(v === 'global' ? 'Using the global speed limit' : `Speed limit: ${label} — applies from the next start`)
}

// Checksum verification: hash the finished file on device, compare with the source's value.
const hashInput = ref('')
const hashResult = ref(null) // { hex, verdict: 'match'|'mismatch'|'done' } or { verdict: 'error', msg }
const hashing = ref(false)

watch(() => route.params.id, () => {
  hashInput.value = ''
  hashResult.value = null
})

async function verifyHash() {
  if (!item.value?.filePath) return
  hashing.value = true
  hashResult.value = null
  try {
    const { hex } = await Downswing.sha256({ path: item.value.filePath })
    const expected = hashInput.value.trim().toLowerCase().replace(/^sha-?256[:\s]*/i, '')
    hashResult.value = expected
      ? { hex, verdict: hex === expected ? 'match' : 'mismatch' }
      : { hex, verdict: 'done' }
  } catch (e) {
    hashResult.value = {
      verdict: 'error',
      msg: /not implemented/i.test(e?.message || '')
        ? 'Checksums only work in the Android app.'
        : e?.message || 'Could not read that file.',
    }
  } finally {
    hashing.value = false
  }
}

const rows = computed(() => {
  const i = item.value
  return [
    ['URL', i.url],
    ['Save location', i.filePath || i.saveTo || (i.folder || 'App folder')],
    ['Type', KIND_LABEL[i.kind] || 'Video'],
    (i.kind === 'video' || !i.kind) && ['Quality', i.audioOnly ? 'Audio only (MP3)' : `Up to ${i.quality}p`],
    ['Added', formatDate(i.createdAt)],
    i.startedAt && ['Started', formatDate(i.startedAt)],
    i.finishedAt && ['Finished', formatDate(i.finishedAt)],
  ].filter(Boolean)
})
</script>

<template>
  <header class="top">
    <button class="icon-btn" aria-label="Back" @click="router.back()"><HugeiconsIcon :icon="ArrowLeft01Icon" :size="24" :stroke-width="2.2" /></button>
    <h1 class="title-sm">Download details</h1>
  </header>

  <div v-if="!item" class="empty"><strong>This download is gone</strong><span>It was removed from the list.</span></div>

  <template v-else>
    <div class="d-head">
      <FileTile :name="item.name" :audio-only="item.audioOnly" :source="item.kind" :size="52" :state="tileState" />
      <input v-if="renaming" ref="nameInput" v-model="renameDraft" class="rename-input" aria-label="Download name"
        @keydown.enter="saveRename" @keydown.esc="renaming = false">
      <div v-else class="d-name">{{ item.name }}</div>
      <button class="inline-btn" :aria-label="renaming ? 'Save name' : 'Rename download'" @click="renaming ? saveRename() : startRename()">
        <HugeiconsIcon :icon="renaming ? CheckIcon : Edit01Icon" :size="16" :stroke-width="2.2" />
      </button>
    </div>

    <div class="d-stage">
      <div class="ring" :class="[statusClass, { live: item.status === 'downloading' }]" :style="{ '--pct': item.progress }">
        <span class="num">{{ item.progress }}%</span>
      </div>
      <div class="d-facts">
        <div class="d-status" :class="statusClass">{{ status }}</div>
        <div v-if="item.totalText" class="num">{{ rangeText(item.downloadedText, item.totalText) }}</div>
        <div v-if="item.speed || item.eta" class="num muted">{{ [item.speed, item.eta].filter(Boolean).join(', ') }}</div>
        <div v-if="item.peers && item.status === 'downloading'" class="num muted">{{ item.peers }}</div>
        <div v-if="item.status === 'error'" class="bad small">{{ item.error }}</div>
      </div>
    </div>

    <div v-if="item.status !== 'completed'" class="actions tight">
      <button class="btn primary" @click="primary">{{ primaryLabel }}</button>
      <button class="btn ghost" @click="cancel">Cancel</button>
    </div>
    <div v-else class="actions tight">
      <button class="btn primary" :disabled="!item.filePath" @click="openFile"><HugeiconsIcon :icon="FolderOpenIcon" :size="16" :stroke-width="2.2" /> Open</button>
      <button class="btn ghost" :disabled="!item.filePath" @click="shareFile"><HugeiconsIcon :icon="Share01Icon" :size="16" :stroke-width="2.2" /> Share</button>
    </div>
    <div v-if="item.status === 'completed'" class="after-actions">
      <button class="text-btn" @click="removeFromList">Remove from list</button>
    </div>

    <div v-if="item.status !== 'completed'" class="group limit-row">
      <div class="setting">
        <span class="s-label"><HugeiconsIcon :icon="GaugeIcon" :size="16" /> Speed limit for this download</span>
        <select class="plain right" :value="limitValue" @change="setLimit">
          <option v-for="[v, label] in LIMITS" :key="v" :value="v">{{ label }}</option>
        </select>
      </div>
    </div>

    <dl class="facts">
      <div v-for="[k, v] in rows" :key="k"><dt>{{ k }}</dt><dd>{{ v }}<button v-if="k === 'URL'" class="inline-btn copy-url" aria-label="Copy link" @click="copyUrl"><HugeiconsIcon :icon="Copy01Icon" :size="15" /></button></dd></div>
    </dl>

    <div v-if="item.status === 'completed' && item.filePath" class="block verify">
      <span class="label"><HugeiconsIcon :icon="ShieldCheckIcon" :size="16" /> Verify file (SHA-256)</span>
      <div class="field">
        <input v-model="hashInput" type="text" spellcheck="false" autocomplete="off" autocapitalize="off"
          placeholder="Expected SHA-256 — paste from the source page" @keydown.enter="verifyHash">
        <button class="inline-btn text" :disabled="hashing" @click="verifyHash">{{ hashing ? 'Checking' : 'Check' }}</button>
      </div>
      <p v-if="hashResult" class="hint" :class="hashResult.verdict">
        <template v-if="hashResult.verdict === 'match'">Checksum matches — the file is intact.</template>
        <template v-else-if="hashResult.verdict === 'mismatch'">Checksum does not match — this file differs from the source.</template>
        <template v-else-if="hashResult.verdict === 'done'">SHA-256 computed — paste the expected value to compare.</template>
        <template v-else>{{ hashResult.msg }}</template>
      </p>
      <p v-if="hashResult?.hex" class="hash num">{{ hashResult.hex }}</p>
    </div>
  </template>
</template>
