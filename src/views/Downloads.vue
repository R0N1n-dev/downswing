<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HugeiconsIcon } from '@hugeicons/vue'
import { Search01Icon, PlusIcon, Cancel01Icon, GearsIcon, PauseIcon, PlayIcon, CheckIcon, Delete01Icon, Hamburger01Icon } from '@hugeicons/core-free-icons'
import { toggleDrawer } from '../lib/drawer'
import { storeToRefs } from 'pinia'
import { Filesystem, Directory } from '@capacitor/filesystem'
import { useDownloadsStore } from '../stores/downloads'
import { SECTIONS } from '../lib/sections'
import { KIND_LABEL } from '../lib/detect'
import { formatBytes, parseSize, rangeText, finishedTodayBytes, fileKind, dayLabel } from '../lib/format'
import { showToast } from '../lib/toast'
import { Downswing } from '../services/downswing'
import DownloadRow from '../components/DownloadRow.vue'

const route = useRoute()
const router = useRouter()
const store = useDownloadsStore()
const { items, speedHistory } = storeToRefs(store)

const section = computed(() => SECTIONS.find((s) => s.id === route.meta.section) || SECTIONS[0])
const searching = ref(false)
const query = ref('')
const typeFilter = ref('')

// Multi-select on the finished lists: pick rows, delete in one go, undo from the snackbar.
const selecting = ref(false)
const picked = ref(new Set())

const TYPES = [['video', 'Video', 'tv'], ['audio', 'Audio', 'ta'], ['image', 'Image', 'ti'], ['archive', 'Archive', 'tk'], ['doc', 'Doc', 'td']]

watch(section, () => {
  searching.value = false
  query.value = ''
  typeFilter.value = ''
  selecting.value = false
  picked.value = new Set()
})
watch(searching, (on) => { if (on) { selecting.value = false; picked.value = new Set() } })

const ORDER = { downloading: 0, retrying: 1, paused: 2, queued: 3, error: 4, completed: 5 }
const newest = (a, b) => (b.finishedAt || b.createdAt) - (a.finishedAt || a.createdAt)

const canSelect = computed(() => ['completed', 'failed'].includes(section.value.id))

const visible = computed(() => {
  const q = query.value.trim().toLowerCase()
  // Searching looks across every download, not just the open section.
  const source = q ? items.value : items.value.filter(section.value.match)
  let list = source.filter((i) => !q || i.name.toLowerCase().includes(q))
  if (typeFilter.value) list = list.filter((i) => fileKind(i.name, i.audioOnly, i.kind) === typeFilter.value)
  switch (section.value.id) {
    case 'queued': return list // keep the order the user arranged
    case 'completed':
    case 'failed': return list.slice().sort(newest)
    default: return list.slice().sort((a, b) => ORDER[a.status] - ORDER[b.status])
  }
})

// Header stats, all computed from real items: active count, summed speed, bytes finished today.
const subtitle = computed(() => {
  const active = items.value.filter((i) => i.status === 'downloading')
  const parts = []
  if (active.length) parts.push(`${active.length} active`)
  const bps = active.reduce((sum, i) => sum + parseSize(i.speed), 0)
  if (bps > 0) parts.push(`${formatBytes(bps)}/s`)
  const today = finishedTodayBytes(items.value)
  if (today > 0) parts.push(`${formatBytes(today)} today`)
  return parts.join(' · ') || 'No active downloads'
})

// One tap to stop or restart the whole lane — the same pause the exit sheet offers.
const runningCount = computed(() => items.value.filter((i) => ['downloading', 'retrying', 'queued'].includes(i.status)).length)
const heldCount = computed(() => items.value.filter((i) => ['paused', 'error'].includes(i.status)).length)
async function toggleAll() {
  if (runningCount.value) {
    const n = await store.pauseAll()
    showToast(n ? `Paused ${n} download${n === 1 ? '' : 's'}` : 'All downloads paused')
  } else {
    store.resumeAll()
    showToast('Queue resumed')
  }
}

// The hero is the one lit object: the active transfer (or the most recent pause on All).
const hero = computed(() => {
  if (query.value.trim() || selecting.value || !['all', 'downloading'].includes(section.value.id)) return null
  return visible.value.find((i) => i.status === 'downloading')
    || (section.value.id === 'all' ? visible.value.find((i) => i.status === 'paused') : null)
})
const rows = computed(() => (hero.value ? visible.value.filter((i) => i.id !== hero.value.id) : visible.value))

const heroSub = computed(() => {
  const i = hero.value
  if (!i) return ''
  const parts = []
  if (i.totalText) parts.push(rangeText(i.downloadedText, i.totalText))
  if (i.eta) parts.push(`${i.eta} left`)
  return parts.join(' · ') || (i.status === 'paused' ? 'Paused' : 'Starting…')
})

const heroTags = computed(() => {
  const i = hero.value
  if (!i) return []
  return [
    i.speed && { text: i.speed, hot: true },
    i.peers && { text: i.peers },
    { text: i.audioOnly ? 'Audio' : KIND_LABEL[i.kind] || 'Video' },
  ].filter(Boolean)
})

// Last ~80s of combined speed, sampled every 2s in the store; hidden until real activity.
const spark = computed(() => {
  const h = speedHistory.value
  if (h.length < 2) return null
  const max = Math.max(...h, 1)
  if (max <= 1) return null
  const pts = h.map((v, i) => `${((i / (h.length - 1)) * 100).toFixed(2)},${(26 - (v / max) * 24).toFixed(1)}`)
  return { line: pts.join(' '), area: `0,28 ${pts.join(' ')} 100,28` }
})

const canClear = computed(() => ['completed', 'failed'].includes(section.value.id) && visible.value.length > 0)

function clear() {
  const id = section.value.id
  if (confirm(`Clear all ${id} downloads from the list? Downloaded files stay on your device.`)) store.clearFinished(id)
}

function primary(item) {
  if (item.status === 'downloading') store.pause(item.id)
  else if (['queued', 'paused', 'retrying'].includes(item.status)) store.startNow(item.id)
  else store.resume(item.id)
}

function onRow(item) {
  if (selecting.value) togglePick(item)
  else router.push(`/download/${item.id}`)
}

// Cancel = stop the job and drop the record; the snackbar can bring it back.
function cancelRow(item) {
  const gone = { ...item }
  store.cancel(item.id)
  showToast(`${item.name} cancelled`, 4500, { label: 'Undo', run: () => store.restore([gone]) })
}

function togglePick(item) {
  const next = new Set(picked.value)
  next.has(item.id) ? next.delete(item.id) : next.add(item.id)
  picked.value = next
}
function startSelect() {
  selecting.value = true
  picked.value = new Set()
}
function selectAll() {
  picked.value = picked.value.size === visible.value.length
    ? new Set()
    : new Set(visible.value.map((i) => i.id))
}
const pickedItems = computed(() => items.value.filter((i) => picked.value.has(i.id)))
const pickedBytes = computed(() => pickedItems.value.reduce((sum, i) => sum + parseSize(i.totalText), 0))

function deletePicked() {
  const drop = pickedItems.value
  if (!drop.length) return
  drop.forEach((i) => store.remove(i.id))
  selecting.value = false
  picked.value = new Set()
  showToast(`${drop.length} removed from the list`, 4500, { label: 'Undo', run: () => store.restore(drop) })
}

// Done list grouped under day headers: Today, Yesterday, then plain dates.
const grouped = computed(() => {
  if (section.value.id !== 'completed' || query.value) return null
  const out = []
  for (const i of visible.value) {
    const label = dayLabel(i.finishedAt || i.createdAt)
    const last = out[out.length - 1]
    if (last && last.label === label) last.items.push(i)
    else out.push({ label, items: [i] })
  }
  return out
})

// CSV of the whole history, handed to the Android share sheet so it can be saved anywhere.
// In a browser there is no share sheet, so the file is downloaded instead.
const CSV_STATUS = { downloading: 'Downloading', retrying: 'Retrying', queued: 'Queued', paused: 'Paused', completed: 'Completed', error: 'Failed' }

async function exportCsv() {
  const q = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`
  const cols = ['Name', 'URL', 'Type', 'Status', 'Size', 'Folder', 'File', 'Added', 'Started', 'Finished', 'Error']
  const lines = [cols.map(q).join(',')]
  for (const i of items.value) {
    lines.push([
      i.name, i.url, KIND_LABEL[i.kind] || 'Video', CSV_STATUS[i.status] || i.status, i.totalText || '',
      i.folder || 'App folder', i.filePath || '',
      i.createdAt ? new Date(i.createdAt).toISOString() : '',
      i.startedAt ? new Date(i.startedAt).toISOString() : '',
      i.finishedAt ? new Date(i.finishedAt).toISOString() : '',
      i.error || '',
    ].map(q).join(','))
  }
  const csv = lines.join('\r\n')
  const fname = `downswing-history-${new Date().toISOString().slice(0, 10)}.csv`
  try {
    const { uri } = await Filesystem.writeFile({ path: fname, data: csv, directory: Directory.Cache })
    await Downswing.shareFile({ path: decodeURIComponent(uri.replace(/^file:\/\//, '')), text: 'Downswing download history' })
    showToast('Share sheet opened — pick where to save the CSV')
  } catch {
    try {
      const a = document.createElement('a')
      a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }))
      a.download = fname
      a.click()
      URL.revokeObjectURL(a.href)
      showToast(`Exported ${items.value.length} rows`)
    } catch {
      showToast('Could not export the CSV')
    }
  }
}
</script>

<template>
  <header class="top">
    <template v-if="!searching && !selecting">
      <button class="icon-btn" aria-label="Open menu" @click="toggleDrawer">
        <HugeiconsIcon :icon="Hamburger01Icon" :size="20" :stroke-width="2.4" />
      </button>
    </template>

    <template v-if="selecting">
      <button class="icon-btn" aria-label="Leave selection" @click="selecting = false; picked = new Set()">
        <HugeiconsIcon :icon="Cancel01Icon" :size="20" :stroke-width="2.4" />
      </button>
      <div class="t-block">
        <h1>{{ picked.size }} selected</h1>
        <p class="sub">Tap rows to add or remove · {{ formatBytes(pickedBytes) }}</p>
      </div>
      <button class="text-btn" @click="selectAll">All</button>
    </template>

    <template v-else>
      <div v-if="!searching" class="t-block">
        <h1>{{ section.short }}</h1>
        <p class="sub">{{ subtitle }}</p>
      </div>
      <label v-else class="field grow">
        <HugeiconsIcon :icon="Search01Icon" :size="18" />
        <input v-model="query" type="search" placeholder="Search downloads" autofocus>
      </label>
      <button class="icon-btn" :aria-label="searching ? 'Close search' : 'Search'" @click="searching = !searching; query = ''">
        <HugeiconsIcon :icon="searching ? Cancel01Icon : Search01Icon" :size="20" :stroke-width="2.2" />
      </button>
      <button class="icon-btn" aria-label="Settings" @click="router.push('/settings')">
        <HugeiconsIcon :icon="GearsIcon" :size="20" :stroke-width="2" />
      </button>
      <button class="icon-btn hot" aria-label="Add download" @click="router.push('/add')">
        <HugeiconsIcon :icon="PlusIcon" :size="20" :stroke-width="2.6" />
      </button>
    </template>
  </header>

  <div class="feed">
    <nav class="seg" aria-label="Filter downloads">
      <RouterLink v-for="s in SECTIONS" :key="s.id" :to="s.to" :class="{ on: s.id === section.id }">{{ s.short }}</RouterLink>
    </nav>

    <div v-if="!selecting && (items.length || canClear || runningCount || heldCount)" class="tool-row">
      <button v-if="items.length" class="text-btn" @click="exportCsv">Export</button>
      <button v-if="canClear" class="text-btn" @click="clear">Clear</button>
      <button v-if="canSelect && visible.length" class="text-btn" @click="startSelect">Select</button>
      <button v-if="runningCount || heldCount" class="text-btn" :aria-label="runningCount ? 'Pause every download' : 'Resume the queue'" @click="toggleAll">
        {{ runningCount ? 'Pause all' : 'Resume all' }}
      </button>
    </div>

    <div v-if="section.id === 'completed' && !query" class="chips" role="group" aria-label="Filter by file type">
      <button class="chip" :class="{ on: !typeFilter }" @click="typeFilter = ''">All</button>
      <button v-for="[v, label, cls] in TYPES" :key="v" class="chip" :class="[cls, { on: typeFilter === v }]" @click="typeFilter = v">{{ label }}</button>
    </div>

    <section v-if="hero" class="hero">
      <div class="hero-top">
        <div class="ring" :class="{ live: hero.status === 'downloading' }" :style="{ '--pct': hero.progress }"><span>{{ hero.progress }}%</span></div>
        <div class="hero-body">
          <div class="hero-name">{{ hero.name }}</div>
          <div class="hero-sub">{{ heroSub }}</div>
          <div class="tags">
            <span v-for="(t, idx) in heroTags" :key="idx" class="tag" :class="{ cy: t.hot }">{{ t.text }}</span>
          </div>
        </div>
      </div>
      <svg v-if="spark" class="spark" viewBox="0 0 100 28" preserveAspectRatio="none" role="img" aria-label="Recent download speed">
        <polygon class="ar" :points="spark.area" />
        <polyline class="ln" :points="spark.line" />
      </svg>
      <div class="hero-acts">
        <button class="btn primary" @click="primary(hero)">
          <HugeiconsIcon :icon="hero.status === 'downloading' ? PauseIcon : PlayIcon" :size="16" :stroke-width="2.4" />
          {{ hero.status === 'downloading' ? 'Pause' : 'Resume' }}
        </button>
        <button class="btn ghost" @click="router.push(`/download/${hero.id}`)">Details</button>
        <button class="btn danger" aria-label="Cancel this download" @click="cancelRow(hero)">
          <HugeiconsIcon :icon="Cancel01Icon" :size="16" :stroke-width="2.4" />
        </button>
      </div>
    </section>

    <template v-if="grouped">
      <section v-for="g in grouped" :key="g.label">
        <div class="ghead"><span>{{ g.label }}</span><span class="num">{{ g.items.length }}</span></div>
        <div class="list">
          <DownloadRow
            v-for="i in g.items" :key="i.id" :item="i" :selectable="selecting" :selected="picked.has(i.id)"
            @open="onRow(i)" @primary="primary(i)" @cancel="cancelRow(i)" @toggle="togglePick(i)"
          />
        </div>
      </section>
    </template>
    <div v-else-if="rows.length" class="list">
      <DownloadRow
        v-for="i in rows" :key="i.id" :item="i" :reorder="section.id === 'queued'"
        :selectable="selecting" :selected="picked.has(i.id)"
        @open="onRow(i)" @primary="primary(i)" @cancel="cancelRow(i)" @toggle="togglePick(i)"
        @up="store.move(i.id, -1)" @down="store.move(i.id, 1)"
      />
    </div>
  </div>

  <div v-if="!visible.length" class="empty">
    <span class="empty-mark"><HugeiconsIcon :icon="query ? Search01Icon : section.icon" :size="30" :stroke-width="2" /></span>
    <strong>{{ query ? 'No matches' : typeFilter ? 'No matching files' : section.empty[0] }}</strong>
    <span>{{ query ? 'Nothing matches that search.' : typeFilter ? 'Nothing of that type in your downloads.' : section.empty[1] }}</span>
    <button v-if="!query && !typeFilter && ['all', 'downloading', 'queued'].includes(section.id)" class="btn primary" @click="router.push('/add')">
      <HugeiconsIcon :icon="PlusIcon" :size="16" :stroke-width="2.6" /> Add download
    </button>
  </div>

  <Teleport to=".shell">
    <div v-if="selecting" class="batch-bar">
      <span class="count"><b>{{ picked.size }}</b> · {{ formatBytes(pickedBytes) }}</span>
      <button :disabled="!picked.size" class="danger" @click="deletePicked">
        <HugeiconsIcon :icon="Delete01Icon" :size="15" :stroke-width="2.4" /> Delete
      </button>
    </div>
  </Teleport>
</template>
