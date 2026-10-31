<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useDownloadsStore } from '../stores/downloads'
import { useSettingsStore } from '../stores/settings'
import { formatBytes, finishedTodayBytes } from '../lib/format'

const { items, waitingCharger } = storeToRefs(useDownloadsStore())
const settings = useSettingsStore().s

const active = computed(() => items.value.filter((i) => i.status === 'downloading').length)
const retrying = computed(() => items.value.filter((i) => i.status === 'retrying').length)
const queued = computed(() => items.value.filter((i) => i.status === 'queued').length)
const today = computed(() => finishedTodayBytes(items.value))

// One quiet line of real numbers, only while work is moving: slots in use, retries, queue
// depth, bytes finished since midnight. App.vue mounts this strip only when the queue is busy,
// so there is no "0 downloads · idle" badge sitting on the queue when nothing is happening.
const stripStats = computed(() => {
  const parts = []
  if (active.value) parts.push(`${active.value} of ${settings.maxSimultaneous} slots`)
  if (retrying.value) parts.push(`${retrying.value} retrying`)
  if (queued.value) parts.push(`${queued.value} queued`)
  if (waitingCharger.value) parts.push('waiting for charger')
  if (today.value > 0) parts.push(`${formatBytes(today.value)} finished today`)
  return parts.join(' · ')
})

// What the queue is doing right now — never "idle", because the strip is gone by then.
const note = computed(() => (active.value || retrying.value ? 'live' : waitingCharger.value ? 'waiting' : 'queued'))
</script>

<template>
  <div class="stat-strip">
    <span class="strip-stats">{{ stripStats }}</span>
    <span class="strip-note num">{{ note }}</span>
  </div>
</template>
