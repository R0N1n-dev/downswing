<script setup>
import { onMounted, onBeforeUnmount, watch, ref, computed } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { App as CapApp } from '@capacitor/app'
import { Capacitor } from '@capacitor/core'
import { Clipboard } from '@capacitor/clipboard'
import Dock from './components/Dock.vue'
import SideNav from './components/SideNav.vue'
import ShareSheet from './components/ShareSheet.vue'
import ConfirmSheet from './components/ConfirmSheet.vue'
import { SECTIONS } from './lib/sections'
import { nextBackAction } from './lib/back'
import { useDownloadsStore } from './stores/downloads'
import { useSettingsStore } from './stores/settings'
import { toast, toastAct, runToastAction, showToast } from './lib/toast'
import { pollShared, consumeShared } from './lib/share'
import { drawerOpen, closeDrawer } from './lib/drawer'
import { detectKind, guessName } from './lib/detect'
import { ensureNotifyPermission } from './services/notify'
import { locked, lockError, checkLock, unlock } from './lib/lock'

const route = useRoute()
const router = useRouter()
const downloads = useDownloadsStore()
const settings = useSettingsStore().s
const screen = ref(null)
const unlocking = ref(false)
const sharedLinks = ref([])
// '' = the exit confirm is closed, 'plain' = "Exit Downly?", 'pause' = "Pause & exit?"
const exitMode = ref('')
let backHandle
let resumeHandle

// The status strip lives at the bottom of section screens, but only while the queue is moving:
// an idle app keeps that space for the list.
const showDock = computed(() => SECTIONS.some((s) => s.id === route.meta.section) && downloads.busy)

watch(() => route.fullPath, () => screen.value?.scrollTo({ top: 0 }))

// A link shared from another app lands here on open and on return to the app.
// It does not jump straight to the Add screen: the action sheet asks what to do with it.
async function intakeShared() {
  const text = await pollShared()
  if (text) sharedLinks.value = text.split(/\s+/).filter(Boolean)
}

function sheetDismiss() {
  consumeShared()
  sharedLinks.value = []
}

function sheetOptions() {
  sharedLinks.value = [] // the Add screen consumes the pending link
  router.push('/add')
}

// Straight into the queue with the current defaults: the fast path out of a share sheet.
function sheetDownload() {
  const pending = consumeShared()
  sharedLinks.value = []
  if (!pending) return
  let added = 0
  let skipped = 0
  let reason = ''
  for (const url of pending.split(/\s+/).filter(Boolean)) {
    try {
      const kind = detectKind(url) === 'torrent' ? 'torrent' : detectKind(url) || 'video'
      downloads.add({
        url,
        name: guessName(url, kind),
        kind,
        audioOnly: false,
        quality: settings.quality,
        folder: settings.folder,
        startNow: settings.startAuto,
      })
      added++
    } catch (e) {
      skipped++
      reason = e.message
    }
  }
  if (!added && skipped) {
    showToast(reason || 'Could not add that link')
    return
  }
  ensureNotifyPermission()
  showToast(skipped ? `Added ${added} · ${skipped} skipped` : `Added ${added} to the queue`, 3000)
  if (route.path !== '/') router.push('/')
}

async function sheetCopy() {
  const link = consumeShared()
  sharedLinks.value = []
  if (!link) return
  try {
    await Clipboard.write({ string: link.split(/\s+/)[0] })
    showToast('Link copied')
  } catch {
    showToast('Clipboard is not available')
  }
}

async function onUnlock() {
  unlocking.value = true
  try { await unlock() } finally { unlocking.value = false }
}

// Downloads still moving: live jobs, pending retries and rows waiting for a slot.
const ongoing = computed(() => downloads.items.filter((i) => ['downloading', 'queued', 'retrying'].includes(i.status)))

// What the queue's back press asks. Live work gets the pause question first; an idle queue
// gets the plain exit confirm. Both read "stay" on cancel, so a stray press never quits.
const exitAsk = computed(() => {
  if (!exitMode.value) return null
  const n = ongoing.value.length
  if (exitMode.value === 'pause' && n) {
    return {
      title: 'Pause & exit?',
      note: `${n} download${n === 1 ? ' is' : 's are'} still running. Pausing ${n === 1 ? 'it' : 'them'} keeps the partial file, so ${n === 1 ? 'it carries' : 'they carry'} on from here next time.`,
      confirm: 'Pause & exit',
      alt: 'Exit without pausing',
      cancel: 'Keep downloading',
    }
  }
  return {
    title: 'Exit Downly?',
    note: 'Nothing is downloading right now. Your finished files stay in the library.',
    confirm: 'Exit',
    alt: '',
    cancel: 'Stay',
  }
})

// Deep links land with no history behind them; in that case the queue is the way out.
function goBack() {
  if (window.history.state?.back) router.back()
  else router.replace('/')
}

function closeExit() {
  exitMode.value = ''
}

// One gesture, walked from the top of the stack down: overlays close, the router steps back to
// the queue, and only the queue itself can leave the app — through a confirm, never on a stray tap.
function handleBack() {
  const action = nextBackAction({
    locked: locked.value,
    drawer: drawerOpen.value,
    sheet: sharedLinks.value.length > 0,
    exitSheet: !!exitMode.value,
    section: route.meta.section,
    path: route.path,
    active: ongoing.value.length,
  })
  switch (action) {
    case 'drawer': return closeDrawer()
    case 'sheet': return sheetDismiss()
    case 'exit-sheet':
      return closeExit()
    case 'history': return goBack()
    case 'root': return router.replace('/')
    case 'ask-pause': exitMode.value = 'pause'
      return
    case 'ask-exit': exitMode.value = 'plain'
      return
    default: return // 'lock': the lock screen swallows every press
  }
}

// Close the app. Downloads left running carry on in the foreground service behind the
// notification, which is why pausing first is the primary answer rather than the only one.
async function leaveApp(paused = 0) {
  exitMode.value = ''
  downloads.persist() // the queue as the user just left it, debounce or not
  try {
    await CapApp.exitApp()
  } catch {
    // Browser preview: there is no process to finish, so report what happened instead.
    const lead = paused ? `Paused ${paused} download${paused === 1 ? '' : 's'} · ` : ''
    showToast(`${lead}close the tab to leave the preview`, 4000)
  }
}

// The sheet's primary answer: stop the lane, then go.
async function pauseAndExit() {
  const n = await downloads.pauseAll()
  await leaveApp(n)
}

function exitConfirmed() {
  if (exitMode.value === 'pause') return pauseAndExit()
  return leaveApp()
}

// The browser preview has no hardware back key, so Escape plays the same part there.
function onKey(e) {
  if (e.key === 'Escape') handleBack()
}

onMounted(async () => {
  downloads.init()
  checkLock(settings.appLock)
  intakeShared()
  try {
    backHandle = await CapApp.addListener('backButton', handleBack)
    resumeHandle = await CapApp.addListener('resume', intakeShared)
  } catch { /* running in a browser */ }
  if (!Capacitor.isNativePlatform()) document.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  backHandle?.remove()
  resumeHandle?.remove()
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div class="shell">
    <div v-if="locked" class="lock-screen" role="dialog" aria-modal="true" aria-label="Downly is locked">
      <div class="lock-mark">
        <svg width="34" height="34" viewBox="0 0 108 108" fill="none" aria-hidden="true">
          <path d="M37.5,82.6 A33,33 0 1 1 70.5,82.6" stroke="currentColor" stroke-width="9" stroke-linecap="round" />
          <path d="M63,28 L39,57 L52,57 L46,82 L69,51 L57,51 Z" fill="#A8E6CF" stroke="#0E0E10" stroke-width="4" stroke-linejoin="round" />
        </svg>
      </div>
      <h1 class="lock-title">Downly is locked</h1>
      <p class="lock-sub">Confirm your fingerprint, face or screen lock to get in.</p>
      <p v-if="lockError" class="lock-err" role="alert">{{ lockError }}</p>
      <button class="btn primary" :disabled="unlocking" @click="onUnlock">{{ unlocking ? 'Checking…' : 'Unlock' }}</button>
    </div>

    <SideNav />

    <div class="stage">
      <main ref="screen" class="screen" :class="{ 'with-dock': showDock }">
        <RouterView />
      </main>

      <Dock v-if="showDock" />
    </div>

    <ShareSheet v-if="sharedLinks.length" :links="sharedLinks"
      @download="sheetDownload" @options="sheetOptions" @copy="sheetCopy" @dismiss="sheetDismiss" />

    <ConfirmSheet v-if="exitAsk" :title="exitAsk.title" :note="exitAsk.note"
      :confirm-label="exitAsk.confirm" :alt-label="exitAsk.alt" :cancel-label="exitAsk.cancel"
      @confirm="exitConfirmed" @alt="leaveApp()" @dismiss="closeExit" />

    <div v-if="toast" class="toast" role="status">
      <span>{{ toast }}</span>
      <button v-if="toastAct" class="act" @click="runToastAction">{{ toastAct.label }}</button>
    </div>
  </div>
</template>
