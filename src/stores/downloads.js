import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { Network } from '@capacitor/network'
import { YtDlp } from '../services/ytdlp'
import { Aria2 } from '../services/aria2'
import { Downswing } from '../services/downswing'
import { showToast } from '../lib/toast'
import { notify } from '../services/notify'
import { useSettingsStore } from './settings'
import { formatFor } from '../lib/options'
import { formatBytes, formatEta, parseSize, basename, hostOf, friendlyError } from '../lib/format'
import { sanitizeUrl, isInstagram } from '../lib/detect'

// Deliberately still the pre-rename namespace: an install that already has a queue under
// "downly.downloads" keeps it, and new writes land under the same key.
const KEY = 'downly.downloads'
const LIVE = ['downloading']
let counter = 0

// Auto-retry: only for transient trouble (drops, timeouts, 5xx, rate limits) — never for
// dead links or blocked media, where a retry would just burn time. Three attempts, growing wait.
const RETRYABLE = /network|timed?\s?out|connection|econn|unreachable|temporary failure|resolution of|reset|broken pipe|socket|unstable|temporarily|http error 5\d\d|\b5\d\d\b|429|rate.?limit|errno|etimedout|urlopen/i
const FATAL = /404|not found|403|forbidden|private video|sign in|region|geo|not a valid url|unable to extract|no video formats|unsupported url|no space|disk full|permission denied|cancelled|not implemented/i
const BACKOFF = [5000, 15000, 45000]
const retryTimers = new Map()

function clearRetry(id) {
  const t = retryTimers.get(id)
  if (t) clearTimeout(t)
  retryTimers.delete(id)
}

const RE_STAT = /\[download\]\s+([\d.]+)%\s+of\s+~?\s*([\d.]+\s*[KMGT]?i?B)(?:\s+at\s+([\d.]+\s*[KMGT]?i?B\/s))?/
const RE_DEST = /\[download\] Destination: (.+)$/
const RE_MERGE = /\[Merger\] Merging formats into "(.+)"/
const RE_AUDIO = /\[ExtractAudio\] Destination: (.+)$/
const RE_FORMATS = /Downloading \d+ format\(s\): (\S+)/

// yt-dlp errors arrive as a wall of stderr. Keep the ERROR line.
function tidy(msg) {
  const m = /ERROR:[^\n]*/.exec(msg || '')
  return (m ? m[0] : msg || 'Download failed').slice(0, 300)
}

function load() {
  try {
    const list = JSON.parse(localStorage.getItem(KEY) || '[]')
    // A download that was running when the app died is resumable, not running.
    return list.map((i) => ({ kind: 'video', peers: '', retries: 0, limitKb: null, ...i,
      // A download or a pending retry that was running when the app died is resumable, not running.
      ...(LIVE.includes(i.status) || i.status === 'retrying' ? { status: 'paused', speed: '', eta: '' } : {}) }))
  } catch {
    return []
  }
}

export const useDownloadsStore = defineStore('downloads', () => {
  const items = ref(load())
  const settings = useSettingsStore().s
  // Declared up here: widgetLabel() reads them and runs from an immediate watch below.
  const charging = ref(true) // optimistic: never hold the queue while the answer is unknown
  const waitingCharger = ref(false)
  let listening = false
  let saveTimer

  // Combined speed sampled every 2s; the hero sparkline renders the last 40 samples.
  const speedHistory = ref([])
  setInterval(() => {
    const bps = items.value.filter((i) => i.status === 'downloading').reduce((sum, i) => sum + parseSize(i.speed), 0)
    speedHistory.value.push(bps)
    if (speedHistory.value.length > 40) speedHistory.value.shift()
    // Keep the widget's speed line live while downloads run.
    if (items.value.some((i) => i.status === 'downloading')) pushWidget()
  }, 2000)

  // Anything in motion: live jobs, pending retries, rows waiting for a slot or for the charger.
  // The status strip only earns its place on screen while this is true, so an idle app has a
  // clean bottom edge instead of a badge that says nothing happened.
  const busy = computed(() =>
    waitingCharger.value || items.value.some((i) => ['downloading', 'retrying', 'queued'].includes(i.status))
  )

  const engineOf = (item) => (item.kind === 'file' || item.kind === 'torrent' ? Aria2 : YtDlp)
  const byId = (id) => items.value.find((i) => i.id === id)
  const byJob = (jobId) => items.value.find((i) => i.jobId === jobId)

  // Write the list right now instead of waiting for the debounce below. Used before the app
  // closes: pausing and quitting in one tap must not lose the paused state on the way out.
  function persist() {
    clearTimeout(saveTimer)
    const clean = items.value.map((i) => Object.fromEntries(Object.entries(i).filter(([k]) => !k.startsWith('_'))))
    try { localStorage.setItem(KEY, JSON.stringify(clean)) } catch { /* storage full */ }
  }

  function save() {
    clearTimeout(saveTimer)
    saveTimer = setTimeout(persist, 800)
  }
  watch(items, save, { deep: true })

  // Tell the native side how much work is pending so the foreground service outlives the screen.
  // The same payload drives the home-screen widget headline.
  let lastWidgetLabel = null
  let widgetPrimed = false

  // One line that says what the app is doing: active count, live speed, queue depth.
  // It drives both the home-screen widget and the Android foreground notification.
  function widgetLabel() {
    const active = items.value.filter((i) => i.status === 'downloading')
    const bps = active.reduce((sum, i) => sum + parseSize(i.speed), 0)
    const queued = items.value.filter((i) => i.status === 'queued').length
    const parts = []
    if (active.length) parts.push(`${active.length} active${bps > 0 ? ` · ${formatBytes(bps)}/s` : ''}`)
    if (queued) parts.push(`${queued} queued`)
    if (waitingCharger.value) parts.push('waiting for charger')
    if (!parts.length && items.value.length && items.value.every((i) => i.status === 'paused')) {
      parts.push(`${items.value.length} paused`)
    }
    return parts.join(' · ')
  }

  function pushWidget() {
    const count = items.value.filter((i) => i.status === 'queued' || i.status === 'downloading').length
    const label = widgetLabel()
    const unchanged = count === 0 && label === lastWidgetLabel
    if (widgetPrimed && unchanged) return
    widgetPrimed = true
    lastWidgetLabel = label
    Downswing.setPending({ count, label }).catch(() => {})
  }

  watch(
    () => items.value.filter((i) => i.status === 'queued' || i.status === 'downloading').length,
    () => pushWidget(),
    { immediate: true },
  )

  // Completion vibration: fire once per download, the first time it completes.
  const vibrateOn = new Set(items.value.filter((i) => i.status === 'completed').map((i) => i.id))
  watch(
    () => items.value.filter((i) => i.status === 'completed').map((i) => i.id),
    (ids) => {
      const fresh = ids.some((id) => !vibrateOn.has(id))
      ids.forEach((id) => vibrateOn.add(id))
      if (fresh && settings.vibrateOnFinish) navigator.vibrate?.([35, 60, 35])
    },
  )

  // "Only download while charging" gate: hold the queue until the phone is plugged in.

  async function refreshCharging() {
    try {
      const res = await Downswing.isCharging()
      charging.value = !!res.charging
    } catch {
      charging.value = true // browser preview: no battery info, never hold the queue
    }
  }

  watch(() => settings.chargingOnly, (on) => {
    if (on) refreshCharging().then(pump)
    else {
      charging.value = true
      waitingCharger.value = false
      pump()
    }
  })

  // Plugging in raises no web event, so re-check while the gate actually matters.
  setInterval(() => {
    if (settings.chargingOnly && items.value.some((i) => i.status === 'queued')) {
      refreshCharging().then(pump)
    }
  }, 15000)
  if (settings.chargingOnly) refreshCharging().then(pump)

  function fail(item, message, nativeNotice) {
    const raw = tidy(message)
    item.jobId = null
    item.speed = ''
    item.eta = ''
    const transient = RETRYABLE.test(raw) && !FATAL.test(raw)
    if (transient && (item.retries || 0) < 3) {
      // Hold it in a retrying state, then drop it back in the queue after the backoff.
      item.status = 'retrying'
      item.error = friendlyError(raw)
      clearRetry(item.id)
      retryTimers.set(item.id, setTimeout(() => {
        retryTimers.delete(item.id)
        if (item.status !== 'retrying') return
        item.retries = (item.retries || 0) + 1
        item.status = 'queued'
        pump()
      }, BACKOFF[item.retries || 0]))
      return
    }
    item.status = 'error'
    item.error = /not implemented/i.test(raw) ? 'Downloads only work in the Android app.' : friendlyError(raw)
    // Widget jobs already posted a native failure notice.
    if (settings.notifyFail && !nativeNotice) notify('Download failed', `${item.name} — ${item.error}`)
    pump()
  }

  function complete(item, path, name, nativeNotice) {
    clearRetry(item.id)
    item.retries = 0
    item.status = 'completed'
    item.progress = 100
    item.jobId = null
    item.speed = ''
    item.eta = ''
    item.peers = ''
    item.finishedAt = Date.now()
    if (name) item.name = name
    item.filePath = path || item.filePath || ''
    // Publish to the media store so the file shows up in Gallery/Files right away.
    if (item.filePath) Downswing.scanFile({ path: item.filePath }).catch(() => {})
    // Widget jobs already posted a native completion notice.
    if (settings.notifyComplete && !nativeNotice) {
      const facts = [item.totalText, item.audioOnly ? 'audio' : `up to ${item.quality}p`].filter(Boolean).join(' · ')
      notify('Download complete', facts ? `${item.name} — ${facts}` : item.name)
    }
    pump()
  }

  async function init() {
    if (listening) return
    listening = true
    try {
      await YtDlp.addListener('downloadProgress', onProgress)
      await YtDlp.addListener('downloadComplete', (e) => {
        const item = byJob(e.id)
        if (item) complete(item, item.filePath || e.outputPath)
      })
      await YtDlp.addListener('downloadError', (e) => {
        const item = byJob(e.id)
        if (item) fail(item, e.message || 'Download failed')
      })

      await Aria2.addListener('aria2Progress', onAriaProgress)
      await Aria2.addListener('aria2Complete', (e) => {
        const item = byJob(e.id)
        if (item) complete(item, e.outputPath, e.name)
      })
      await Aria2.addListener('aria2Error', (e) => {
        const item = byJob(e.id)
        if (item) fail(item, e.message || 'Download failed')
      })
    } catch {
      // No native plugins (browser preview). Adding a download will surface a clear error instead.
      listening = false
    }
    adoptWidgetJobs()
  }

  // ---- widget quick-adds: adopt native jobs, then poll while one is live ----
  let widgetPoll

  async function fetchWidgetJobs() {
    const { jobs } = await Downswing.listWidgetJobs()
    return JSON.parse(jobs || '[]')
  }

  function scheduleWidgetPoll() {
    const needed = items.value.some((i) => i.wid && i.status === 'downloading')
    if (needed && !widgetPoll) widgetPoll = setInterval(pollWidgetJobs, 1500)
    if (!needed && widgetPoll) {
      clearInterval(widgetPoll)
      widgetPoll = null
    }
  }

  /** Downloads started from the home-screen widget, before (or without) this app opening. */
  async function adoptWidgetJobs() {
    let jobs
    try {
      jobs = await fetchWidgetJobs()
    } catch {
      return // browser preview: no native runner
    }
    for (const j of jobs) {
      if (!j?.id) continue
      if (items.value.some((i) => i.wid === j.id)) continue
      if (items.value.some((i) => i.url === j.url && ['queued', 'downloading', 'paused', 'retrying'].includes(i.status))) continue
      const status = { running: 'downloading', paused: 'paused', completed: 'completed', error: 'error' }[j.status] || 'paused'
      items.value.unshift({
        id: j.id,
        wid: j.id,
        jobId: null,
        kind: 'video', // the widget runner always uses yt-dlp
        selectFile: '',
        peers: '',
        url: j.url,
        name: j.name || hostOf(j.url),
        quality: settings.quality,
        audioOnly: false,
        folder: '/storage/emulated/0/Download',
        saveTo: '',
        filePath: j.path || '',
        status,
        progress: j.progress || 0,
        eta: j.eta || '',
        speed: j.speed || '',
        downloadedText: '',
        totalText: '',
        error: j.error || '',
        retries: 0,
        limitKb: null,
        createdAt: j.createdAt || Date.now(),
        startedAt: j.createdAt || null,
        finishedAt: j.finishedAt || null,
      })
      // Terminal records were already announced natively; ingest and drop them so a cleared
      // history row cannot resurrect on the next launch.
      if (status === 'completed' || status === 'error') Downswing.removeWidgetJob({ id: j.id }).catch(() => {})
    }
    scheduleWidgetPoll()
  }

  async function pollWidgetJobs() {
    let jobs
    try {
      jobs = await fetchWidgetJobs()
    } catch {
      scheduleWidgetPoll()
      return
    }
    const byWid = new Map(jobs.map((j) => [j.id, j]))
    for (const item of items.value.filter((i) => i.wid)) {
      const j = byWid.get(item.wid)
      if (!j) continue
      if (j.status === 'running') {
        if (item.status !== 'downloading') item.status = 'downloading' // app was reloaded mid-run
        item.progress = j.progress ?? item.progress
        item.speed = j.speed || ''
        item.eta = j.eta || ''
        if (j.name) item.name = j.name
      } else if (j.status === 'completed' && item.status !== 'completed') {
        complete(item, j.path || item.filePath, j.name, true)
        Downswing.removeWidgetJob({ id: item.wid }).catch(() => {})
        item.wid = null
      } else if (j.status === 'error' && item.status !== 'error') {
        fail(item, j.error || 'Download failed', true)
        Downswing.removeWidgetJob({ id: item.wid }).catch(() => {})
        item.wid = null
      } else if (j.status === 'paused' && item.status === 'downloading') {
        item.status = 'paused'
        item.speed = ''
        item.eta = ''
      }
    }
    scheduleWidgetPoll()
  }

  function onAriaProgress(e) {
    const item = byJob(e.id)
    if (!item || item.status !== 'downloading') return
    if (e.name) item.name = e.name
    if (e.progress >= 0) item.progress = Math.min(99, e.progress)
    if (e.total > 0) {
      item.totalText = formatBytes(e.total)
      item.downloadedText = formatBytes(e.downloaded)
    }
    item.speed = e.speed > 0 ? `${formatBytes(e.speed)}/s` : ''
    item.eta = e.eta >= 0 ? formatEta(e.eta) : ''
    if (item.kind === 'torrent') {
      const peers = `${e.connections} ${e.connections === 1 ? 'peer' : 'peers'}`
      item.peers = e.total > 0 ? peers : `Getting torrent info (${peers})`
    }
  }

  function onProgress(e) {
    const item = byJob(e.id)
    if (!item || item.status !== 'downloading') return
    const line = e.line || ''

    const fmt = RE_FORMATS.exec(line)
    if (fmt) item._streams = fmt[1].split('+').length

    const dest = RE_DEST.exec(line)
    if (dest) {
      item._stream = (item._stream ?? -1) + 1
      item.filePath = dest[1].trim()
      item.name = basename(item.filePath)
    }
    const merged = RE_MERGE.exec(line) || RE_AUDIO.exec(line)
    if (merged) {
      item.filePath = merged[1].trim()
      item.name = basename(item.filePath)
    }

    const stat = RE_STAT.exec(line)
    let pct = e.progress >= 0 ? e.progress : stat ? parseFloat(stat[1]) : -1
    if (pct >= 0) {
      const streams = item._streams || 1
      const done = Math.max(item._stream ?? 0, 0)
      item.progress = Math.min(99, Math.round(((done * 100 + pct) / streams)))
    }
    if (stat) {
      const total = parseSize(stat[2])
      item.totalText = formatBytes(total)
      item.downloadedText = formatBytes((total * parseFloat(stat[1])) / 100)
      if (stat[3]) item.speed = `${formatBytes(parseSize(stat[3]))}/s`
    }
    if (e.etaSeconds >= 0) item.eta = formatEta(e.etaSeconds)
  }

  async function start(item) {
    // Resuming a widget job transfers ownership to the web engine; the native record goes.
    if (item.wid) {
      const wid = item.wid
      item.wid = null
      Downswing.removeWidgetJob({ id: wid }).catch(() => {})
    }
    item.status = 'downloading'
    item.error = ''
    item.startedAt = item.startedAt || Date.now()
    item._stream = -1
    item._streams = item.audioOnly ? 1 : 2
    item.peers = ''
    try {
      if (settings.wifiOnly) {
        const net = await Network.getStatus()
        if (net.connectionType === 'cellular') throw new Error('Wi-Fi only is on and you are on mobile data.')
      }
      // Per-download limit wins over the global one: null = follow global, 0 = unlimited.
      const rateLimitKb = item.limitKb == null ? settings.maxSpeedKb : item.limitKb
      // Instagram hides some posts behind a login: send the saved cookie only to those links,
      // so other sites keep their own (clean) request headers.
      const cookieHeader = item.kind === 'video' && isInstagram(item.url) && settings.igCookie
        ? settings.igCookie.trim()
        : ''
      const res = item.kind === 'video'
        ? await YtDlp.start({
            url: item.url,
            outputDir: item.folder,
            format: formatFor(item.quality, item.audioOnly),
            convertAudio: item.audioOnly,
            audioFormat: 'mp3',
            rateLimitKb,
            ...(cookieHeader ? { cookieHeader } : {}),
          })
        : await Aria2.start({
            source: item.url,
            outputDir: item.folder,
            rateLimitKb,
            selectFile: item.selectFile || '',
          })
      if (item.status !== 'downloading') {
        // Paused or cancelled while the job was starting.
        engineOf(item).cancel({ id: res.id }).catch(() => {})
        return
      }
      item.jobId = res.id
      item.saveTo = res.outputDir
      if (item.folder && res.outputDir !== item.folder) {
        showToast('That folder is not writable, so this went to the app folder.', 4200)
      }
    } catch (err) {
      fail(item, err?.message || String(err))
    }
  }

  function pump() {
    const max = Math.max(1, settings.maxSimultaneous)
    let active = items.value.filter((i) => i.status === 'downloading').length
    const holdForCharger = settings.chargingOnly && !charging.value
    waitingCharger.value = false
    for (const item of items.value) {
      if (active >= max) break
      if (item.status === 'queued') {
        if (holdForCharger) {
          waitingCharger.value = true
          continue
        }
        start(item)
        active++
      }
    }
  }

  function add({ url: rawUrl, name, quality, audioOnly, folder, startNow, kind = 'video', selectFile = '' }) {
    // Canonical form first, so tracking-parameter twins of the same link count as duplicates.
    const url = sanitizeUrl(rawUrl)
    const dup = items.value.find((i) => i.url === url && ['queued', 'downloading', 'paused', 'retrying'].includes(i.status))
    if (dup) throw new Error('That link is already in your list.')
    // Rule: with the default folder, audio-only downloads land in Music.
    const dest = audioOnly && settings.smartAudioFolder && !folder ? '/storage/emulated/0/Music' : folder
    const item = {
      id: `d${Date.now().toString(36)}${counter++}`,
      jobId: null,
      kind,
      selectFile,
      peers: '',
      url,
      name: name || hostOf(url),
      quality,
      audioOnly,
      folder: dest,
      saveTo: '',
      filePath: '',
      status: startNow ? 'queued' : 'paused',
      progress: 0,
      eta: '',
      speed: '',
      downloadedText: '',
      totalText: '',
      error: '',
      retries: 0,
      limitKb: null, // null = follow the global setting
      createdAt: Date.now(),
      startedAt: null,
      finishedAt: null,
    }
    items.value.unshift(item)
    pump()
    return item.id
  }

  async function stopJob(item) {
    const jobId = item.jobId
    const wid = item.wid
    item.jobId = null
    item.speed = ''
    item.eta = ''
    // Widget-started downloads run in the native runner, not the engine plugins.
    if (wid && !jobId) await Downswing.stopWidgetJob({ id: wid }).catch(() => {})
    else if (jobId) await engineOf(item).cancel({ id: jobId }).catch(() => {})
  }

  async function pause(id) {
    const item = byId(id)
    if (!item || !['downloading', 'queued', 'retrying'].includes(item.status)) return
    clearRetry(id)
    item.status = 'paused'
    await stopJob(item)
    pump()
  }

  function resume(id) {
    const item = byId(id)
    if (!item || !['paused', 'error', 'retrying'].includes(item.status)) return
    clearRetry(id)
    item.retries = 0
    item.status = 'queued'
    pump()
  }

  // The whole lane at once: the queue's "Pause all" and the exit sheet share this, so both
  // stop live jobs at the engine, drop any pending retry and leave queued rows waiting.
  // Returns how many records changed so callers can say what happened.
  async function pauseAll() {
    const live = items.value.filter((i) => ['downloading', 'queued', 'retrying'].includes(i.status))
    await Promise.all(live.map((i) => pause(i.id)))
    return live.length
  }

  // Mirror of pauseAll: every held row goes back in the queue and the pump picks up the limit.
  function resumeAll() {
    const held = items.value.filter((i) => i.status === 'paused')
    held.forEach((i) => resume(i.id))
    return held.length
  }

  // Skip the line: start now even if the simultaneous limit is reached.
  function startNow(id) {
    const item = byId(id)
    if (!item || !['paused', 'queued', 'error', 'retrying'].includes(item.status)) return
    clearRetry(id)
    item.retries = 0
    start(item)
  }

  async function cancel(id) {
    const item = byId(id)
    if (!item) return
    clearRetry(id)
    if (item.wid) Downswing.removeWidgetJob({ id: item.wid }).catch(() => {})
    item.status = 'paused'
    await stopJob(item)
    items.value = items.value.filter((i) => i.id !== id)
    pump()
  }

  function remove(id) {
    const item = byId(id)
    clearRetry(id)
    if (item?.wid) Downswing.removeWidgetJob({ id: item.wid }).catch(() => {})
    items.value = items.value.filter((i) => i.id !== id)
  }

  function clearFinished(kind) {
    const drop = kind === 'failed' ? ['error'] : kind === 'completed' ? ['completed'] : ['completed', 'error']
    items.value.filter((i) => drop.includes(i.status)).forEach((i) => clearRetry(i.id))
    items.value = items.value.filter((i) => !drop.includes(i.status))
  }

  // Undo: put a removed record (and its queue position) back exactly as it was.
  function restore(list) {
    items.value = [...list, ...items.value.filter((i) => !list.some((r) => r.id === i.id))]
    pump()
  }

  function move(id, dir) {
    const waiting = items.value.filter((i) => ['queued', 'paused'].includes(i.status))
    const at = waiting.findIndex((i) => i.id === id)
    const other = waiting[at + dir]
    if (at < 0 || !other) return
    const a = items.value.indexOf(waiting[at])
    const b = items.value.indexOf(other)
    ;[items.value[a], items.value[b]] = [items.value[b], items.value[a]]
  }

  return { items, busy, speedHistory, charging, waitingCharger, byId, init, add, pause, resume, pauseAll, resumeAll, startNow, cancel, remove, restore, clearFinished, move, pump, persist }
})
