<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { HugeiconsIcon } from '@hugeicons/vue'
import { ArrowLeft01Icon, Link01Icon, ClipboardPasteIcon, Folder01Icon, Search01Icon, CheckIcon, FileUploadIcon, File01Icon, Cancel01Icon, EyeIcon, TriangleAlertIcon } from '@hugeicons/core-free-icons'
import { Clipboard } from '@capacitor/clipboard'
import { YtDlp } from '../services/ytdlp'
import { Aria2 } from '../services/aria2'
import { detectKind, guessName, isMagnet, isInstagram, KIND_LABEL, sanitizeUrl } from '../lib/detect'
import { ensureNotifyPermission } from '../services/notify'
import { useDownloadsStore } from '../stores/downloads'
import { useSettingsStore } from '../stores/settings'
import { QUALITIES, FOLDERS } from '../lib/options'
import { showToast } from '../lib/toast'
import { consumeShared } from '../lib/share'
import { Downswing } from '../services/downswing'
import Switch from '../components/Switch.vue'
import FolderAccess from '../components/FolderAccess.vue'
import { formatBytes } from '../lib/format'

const router = useRouter()
const store = useDownloadsStore()
const settings = useSettingsStore().s

const mode = ref('link')
const url = ref('')
const quality = ref(settings.quality)
const audioOnly = ref(false)
const folder = ref(settings.folder)
const startNow = ref(settings.startAuto)
const error = ref('')
const kindChoice = ref('auto')
const torrentFile = ref(null)
const fileInput = ref(null)
const txtInput = ref(null)
const fromClipboard = ref(false)
const fromShared = ref(false)

// A link shared from another app wins; otherwise prefill one sitting on the clipboard.
onMounted(async () => {
  const shared = consumeShared()
  if (shared) {
    url.value = shared
    fromShared.value = true
    return
  }
  try {
    const { value } = await Clipboard.read()
    const v = (value || '').trim()
    if (v && (/^https?:\/\//i.test(v) || isMagnet(v))) {
      url.value = v
      fromClipboard.value = true
    }
  } catch { /* clipboard unavailable in this context */ }
})

// Every http(s)/magnet token in the field: one link, or a whole pasted batch. Canonicalised
// so tracking-parameter twins of the same link preview — and dedupe — as one.
const links = computed(() => (mode.value === 'link' && !torrentFile.value
  ? url.value.split(/[\s,]+/).map((s) => sanitizeUrl(s)).filter((s) => /^https?:\/\//i.test(s) || isMagnet(s))
  : []))

// Import a .txt full of links (one per line or space-separated).
async function onPickTxt(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  try {
    const text = await file.text()
    const found = text.split(/[\s,]+/).map((s) => sanitizeUrl(s)).filter((s) => /^https?:\/\//i.test(s) || isMagnet(s))
    if (!found.length) {
      error.value = 'No links found in that file.'
      return
    }
    url.value = found.join(' ')
    error.value = ''
    showToast(`Imported ${found.length} link${found.length > 1 ? 's' : ''}`)
  } catch {
    error.value = 'Could not read that file.'
  }
}

const detected = computed(() => (torrentFile.value ? 'torrent' : detectKind(url.value)))

// Smart link preview: one native HEAD/GET probe of a single link, so the user sees what
// they are about to get — real size, content type, and a warning when the URL is a web page.
const preview = ref(null)
const probing = ref(false)
const PREVIEWABLE = /^https?:\/\//i
let probeTimer

watch(links, (list) => {
  preview.value = null
  probing.value = false
  clearTimeout(probeTimer)
  if (list.length !== 1 || !PREVIEWABLE.test(list[0])) return
  const target = list[0]
  probeTimer = setTimeout(async () => {
    if (links.value[0] !== target) return
    probing.value = true
    try {
      const res = await Downswing.probe({ url: target })
      if (links.value[0] === target) preview.value = { url: target, ...res }
    } catch {
      // No native side (browser preview) or the probe failed: the card simply stays hidden.
    } finally {
      probing.value = false
    }
  }, 550)
})

const previewHost = computed(() => {
  try { return new URL(preview.value.url).hostname.replace(/^www\./, '') } catch { return '' }
})
const previewTitle = computed(() => preview.value?.filename || guessName(preview.value?.url || '', kind.value))
const previewKind = computed(() => (preview.value ? detectKind(preview.value.filename || preview.value.url) : null))
const previewIsPage = computed(() => !!preview.value?.isWebpage && previewKind.value !== 'file')
const previewSize = computed(() => {
  const n = preview.value?.size
  return n > 0 ? formatBytes(n) : ''
})
const previewWarn = computed(() => {
  if (!preview.value) return ''
  if (preview.value.status && preview.value.status >= 400) {
    return `The server answered ${preview.value.status} — the link may have expired.`
  }
  if (preview.value.isWebpage && kindChoice.value === 'file' && detectKind(preview.value.url) !== 'file') {
    return 'This link opens a web page, not a file. Switch Type to Auto or Video and Downswing will pull the media out of the page.'
  }
  return ''
})

// Extension chip, or PAGE / the content type when the server gave no file name.
const previewBadge = computed(() => {
  const p = preview.value
  if (!p) return ''
  const name = p.filename || ''
  const ext = name.includes('.') ? name.split('.').pop() : ''
  if (ext) return ext.slice(0, 5).toUpperCase()
  if (p.isWebpage) return 'PAGE'
  const t = (p.type || '').split('/').pop() || ''
  return (t.slice(0, 5) || 'FILE').toUpperCase()
})
const igHost = computed(() => isInstagram(url.value))
// Magnets and .torrent links are always torrents. Otherwise the user's choice wins over detection.
const kind = computed(() => {
  if (mode.value === 'search') return 'video'
  if (detected.value === 'torrent') return 'torrent'
  return kindChoice.value !== 'auto' ? kindChoice.value : detected.value // null until a link is typed
})
// Torrent file picker. Optional: with no list loaded, everything downloads.
const fileList = ref(null) // { torrentPath, files: [{ index, path, size, on }] }
const inspecting = ref(false)
let inspectId = ''

const chosen = computed(() => (fileList.value ? fileList.value.files.filter((f) => f.on) : []))
const chosenSize = computed(() => chosen.value.reduce((sum, f) => sum + f.size, 0))
watch([url, torrentFile], () => { fileList.value = null })

const showTypePicker = computed(() => mode.value === 'link' && detected.value !== 'torrent')

const query = ref('')
const searching = ref(false)
const results = ref([])
const picked = ref(new Set())

const canAdd = computed(() => (mode.value === 'link' ? !!torrentFile.value || url.value.trim().length > 0 : picked.value.size > 0))

async function paste() {
  try {
    const { value } = await Clipboard.read()
    if (value && (/^https?:\/\//i.test(value.trim()) || isMagnet(value))) {
      url.value = value.trim()
      error.value = ''
    } else {
      showToast('No link on the clipboard')
    }
  } catch {
    showToast('Clipboard is not available')
  }
}

function readBase64(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result).split(',')[1] || '')
    r.onerror = () => reject(new Error('Could not read that file'))
    r.readAsDataURL(file)
  })
}

async function onPickTorrent(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  error.value = ''
  try {
    const { path } = await Aria2.importTorrent({ base64: await readBase64(file) })
    torrentFile.value = { path, name: file.name.replace(/\.torrent$/i, '') }
    url.value = ''
  } catch (e) {
    error.value = /not implemented/i.test(e?.message) ? 'Torrents only work in the Android app.' : e?.message || 'Could not import the torrent.'
  }
}

async function chooseFiles() {
  error.value = ''
  inspecting.value = true
  inspectId = `i${Date.now()}${Math.random().toString(36).slice(2, 8)}`
  try {
    const res = await Aria2.inspect({ id: inspectId, source: torrentFile.value ? torrentFile.value.path : url.value.trim() })
    if (!res.files.length) throw new Error('No files found in that torrent.')
    fileList.value = { torrentPath: res.torrentPath, files: res.files.map((f) => ({ ...f, on: true })) }
  } catch (e) {
    if (!/cancel/i.test(e?.message || '')) {
      error.value = /not implemented/i.test(e?.message) ? 'Torrents only work in the Android app.' : e?.message || 'Could not read the torrent.'
    }
  } finally {
    inspecting.value = false
  }
}

function cancelInspect() {
  Aria2.cancel({ id: inspectId }).catch(() => {})
}

// "Show Pack/Season 1/ep01.mkv" -> "Show Pack". A single loose file has no folder, so it keeps its own name.
const torrentTitle = (files) => files[0].path.split('/')[0]

async function search() {
  if (!query.value.trim()) return
  searching.value = true
  results.value = []
  picked.value = new Set()
  error.value = ''
  try {
    const res = await YtDlp.search({ query: query.value })
    results.value = res.results
    if (!results.value.length) error.value = 'No results.'
  } catch (e) {
    error.value = /not implemented/i.test(e?.message) ? 'Search only works in the Android app.' : e?.message || 'Search failed.'
  } finally {
    searching.value = false
  }
}

function toggle(i) {
  const next = new Set(picked.value)
  next.has(i) ? next.delete(i) : next.add(i)
  picked.value = next
}

async function submit() {
  error.value = ''
  const base = { quality: quality.value, folder: folder.value, startNow: startNow.value }
  try {
    if (mode.value === 'link') {
      if (fileList.value) {
        if (!chosen.value.length) throw new Error('Select at least one file.')
        const all = chosen.value.length === fileList.value.files.length
        store.add({
          ...base,
          url: fileList.value.torrentPath,
          name: torrentTitle(chosen.value),
          kind: 'torrent',
          audioOnly: false,
          selectFile: all ? '' : chosen.value.map((f) => f.index).join(','),
        })
      } else if (torrentFile.value) {
        store.add({ ...base, url: torrentFile.value.path, name: torrentFile.value.name, kind: 'torrent', audioOnly: false })
      } else {
        const ls = links.value
        if (!ls.length) throw new Error('Enter a link that starts with http(s):// or a magnet link.')
        const doneDups = ls.filter((l) => store.items.some((i) => i.url === l && i.status === 'completed'))
        if (doneDups.length && !confirm(doneDups.length === 1
          ? 'You already downloaded this file. Download it again?'
          : `${doneDups.length} of these were already downloaded. Add them anyway?`)) return
        let added = 0
        let skipped = 0
        let reason = ''
        for (const l of ls) {
          const k = detectKind(l) === 'torrent' ? 'torrent' : kindChoice.value !== 'auto' ? kindChoice.value : (detectKind(l) || 'video')
          try {
            store.add({ ...base, url: l, name: guessName(l, k), kind: k, audioOnly: audioOnly.value && k === 'video' })
            added++
          } catch (e) {
            skipped++
            reason = e.message
          }
        }
        if (!added && skipped === 1) {
          error.value = reason
          return
        }
        ensureNotifyPermission()
        if (added > 1 || (added && skipped)) showToast(`Added ${added}${skipped ? ` · ${skipped} skipped` : ''}`)
        router.replace('/')
        return
      }
    } else {
      for (const i of picked.value) {
        store.add({ ...base, url: results.value[i].url, name: results.value[i].title, kind: 'video', audioOnly: audioOnly.value })
      }
    }
  } catch (e) {
    error.value = e.message
    return
  }
  ensureNotifyPermission()
  router.replace('/')
}
</script>

<template>
  <header class="top">
    <button class="icon-btn" aria-label="Back" @click="router.back()"><HugeiconsIcon :icon="ArrowLeft01Icon" :size="24" :stroke-width="2.2" /></button>
    <h1 class="title-sm">Add download</h1>
  </header>

  <section v-if="mode === 'link'" class="block">
    <label class="label" for="url">Link or magnet</label>
    <div class="field">
      <HugeiconsIcon :icon="Link01Icon" :size="18" />
      <input id="url" v-model="url" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false"
        placeholder="https://… (YouTube, Instagram, TikTok) or magnet:?xt=…" :disabled="!!torrentFile" @keydown.enter="submit">
      <button class="inline-btn" aria-label="Paste from clipboard" @click="paste"><HugeiconsIcon :icon="ClipboardPasteIcon" :size="18" /></button>
    </div>

    <p v-if="fromShared && url && !torrentFile" class="hint">Shared from another app — edit it, or paste over to replace.</p>
    <p v-else-if="fromClipboard && url && !torrentFile" class="hint">Filled in from your clipboard — edit it, or paste over to replace.</p>
    <p v-if="links.length > 1 && !torrentFile" class="hint">{{ links.length }} links detected — all of them will be added.</p>

    <div v-if="probing" class="preview-card dim">
      <HugeiconsIcon :icon="EyeIcon" :size="18" />
      <span class="pv-name">Checking what this link is…</span>
    </div>
    <div v-else-if="preview" class="preview-card" :class="{ warn: !!previewWarn }">
      <div class="pv-top">
        <span class="pv-badge" :class="{ bad: !!previewWarn }">
          <HugeiconsIcon v-if="previewWarn" :icon="TriangleAlertIcon" :size="14" />
          <template v-else>{{ previewBadge }}</template>
        </span>
        <span class="pv-name">{{ previewTitle }}</span>
        <span v-if="previewSize" class="pv-size num">{{ previewSize }}</span>
      </div>
      <div class="pv-meta">
        <span>{{ previewHost }}</span>
        <span v-if="preview.type">· {{ preview.type }}</span>
        <span v-if="previewIsPage && !previewWarn">· web page</span>
      </div>
      <p v-if="previewWarn" class="pv-warn"><HugeiconsIcon :icon="TriangleAlertIcon" :size="14" /> {{ previewWarn }}</p>
    </div>

    <div v-if="torrentFile" class="picked-file">
      <HugeiconsIcon :icon="FileUploadIcon" :size="18" />
      <span class="grow-text">{{ torrentFile.name }}.torrent</span>
      <button class="inline-btn" aria-label="Remove torrent file" @click="torrentFile = null"><HugeiconsIcon :icon="Cancel01Icon" :size="18" /></button>
    </div>
    <button v-else class="link-btn" @click="fileInput.click()"><HugeiconsIcon :icon="FileUploadIcon" :size="16" /> Choose a .torrent file</button>
    <button class="link-btn" @click="txtInput.click()"><HugeiconsIcon :icon="File01Icon" :size="16" /> Import links from a text file</button>
    <p v-if="kind === 'video' && igHost" class="hint">Instagram posts, reels and stories are supported — private or age-restricted posts need you to be logged in.</p>
    <template v-if="kind === 'torrent'">
      <p class="hint">Torrents download over BitTorrent and stop sharing when they finish.</p>

      <div v-if="inspecting" class="inspect">
        <span>Getting the file list</span>
        <button class="text-btn" @click="cancelInspect">Cancel</button>
      </div>
      <button v-else-if="!fileList" class="link-btn" @click="chooseFiles">Choose which files to download</button>

      <template v-if="fileList">
        <div class="files-head">
          <span class="num">{{ chosen.length }} of {{ fileList.files.length }} selected, {{ formatBytes(chosenSize) }}</span>
          <span>
            <button class="text-btn" @click="fileList.files.forEach((f) => (f.on = true))">All</button>
            <button class="text-btn" @click="fileList.files.forEach((f) => (f.on = false))">None</button>
          </span>
        </div>
        <div class="list picks">
          <button v-for="f in fileList.files" :key="f.index" class="pick" :class="{ on: f.on }" role="checkbox" :aria-checked="f.on" @click="f.on = !f.on">
            <span class="check"><HugeiconsIcon v-if="f.on" :icon="CheckIcon" :size="14" :stroke-width="3" /></span>
            <span class="pick-name">{{ f.path }}</span>
            <span class="num muted">{{ formatBytes(f.size) }}</span>
          </button>
        </div>
      </template>
    </template>
    <input ref="fileInput" type="file" accept=".torrent,application/x-bittorrent" hidden @change="onPickTorrent">
    <input ref="txtInput" type="file" accept=".txt,text/plain" hidden @change="onPickTxt">
  </section>

  <section v-if="showTypePicker" class="block">
    <span class="label">Type</span>
    <div class="seg" role="radiogroup" aria-label="Download type">
      <button v-for="k in ['auto', 'video', 'file']" :key="k" role="radio" :aria-checked="kindChoice === k" :class="{ on: kindChoice === k }" @click="kindChoice = k">
        {{ k === 'auto' ? 'Auto' : KIND_LABEL[k] }}
      </button>
    </div>
    <p v-if="kind" class="hint">
      {{ kindChoice === 'auto' ? `Detected as ${KIND_LABEL[kind].toLowerCase()}. ` : '' }}{{ kind === 'video' ? 'Video uses yt-dlp for YouTube, Instagram, TikTok and other media sites.' : 'File downloads the link as is, using up to 16 connections.' }}
    </p>
    <button v-if="kind === 'video'" class="link-btn" @click="mode = 'search'"><HugeiconsIcon :icon="Search01Icon" :size="16" /> Search YouTube instead</button>
  </section>

  <section v-if="mode === 'search'" class="block">
    <label class="label" for="q">Search YouTube</label>
    <div class="field">
      <HugeiconsIcon :icon="Search01Icon" :size="18" />
      <input id="q" v-model="query" type="search" placeholder="What are you looking for?" @keydown.enter="search">
      <button class="inline-btn text" :disabled="searching" @click="search">{{ searching ? 'Searching' : 'Search' }}</button>
    </div>
    <div v-if="results.length" class="list picks">
      <button v-for="(r, i) in results" :key="r.url" class="pick" :class="{ on: picked.has(i) }" @click="toggle(i)">
        <span class="check"><HugeiconsIcon v-if="picked.has(i)" :icon="CheckIcon" :size="14" :stroke-width="3" /></span>
        <span class="pick-name">{{ r.title }}</span>
        <span class="num muted">{{ r.duration }}</span>
      </button>
    </div>
    <button class="link-btn" @click="mode = 'link'"><HugeiconsIcon :icon="Link01Icon" :size="16" /> Paste a link instead</button>
  </section>

  <!-- Instagram hands over exactly one rendition, so there is no ladder to pick from. -->
  <section v-if="kind === 'video' && !igHost" class="block">
    <span class="label">Quality</span>
    <div class="seg five" role="radiogroup" aria-label="Video quality" :class="{ dim: audioOnly }">
      <button v-for="q in QUALITIES" :key="q" role="radio" :aria-checked="quality === q" :disabled="audioOnly" :class="{ on: quality === q }" @click="quality = q">{{ q }}p</button>
    </div>
    <p class="hint">Gets the best stream up to {{ quality }}p. If the video has nothing that low, it takes the closest match.</p>
  </section>

  <p v-else-if="kind === 'video' && igHost" class="hint">Instagram serves one quality per post, reel or story — Downswing grabs the best it offers, so there is nothing to pick.</p>

  <section class="block">
    <label class="label" for="folder">Save to</label>
    <div class="field">
      <HugeiconsIcon :icon="Folder01Icon" :size="18" />
      <select id="folder" v-model="folder">
        <option v-for="f in FOLDERS" :key="f.value" :value="f.value">{{ f.label }}</option>
      </select>
    </div>
  </section>

  <FolderAccess :folder="folder" />

  <div class="group">
    <div v-if="kind === 'video'" class="setting"><span>Audio only (MP3)</span><Switch v-model="audioOnly" label="Audio only" /></div>
    <div class="setting"><span>Start download immediately</span><Switch v-model="startNow" label="Start immediately" /></div>
  </div>

  <p v-if="error" class="error" role="alert">{{ error }}</p>

  <div class="actions">
    <button class="btn ghost" @click="router.back()">Cancel</button>
    <button class="btn primary" :disabled="!canAdd" @click="submit">{{ mode === 'search' && picked.size > 1 ? `Add ${picked.size}` : 'Add' }}</button>
  </div>
</template>
