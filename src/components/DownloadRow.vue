<script setup>
import { computed } from 'vue'
import { HugeiconsIcon } from '@hugeicons/vue'
import {
  ChevronUpIcon, ChevronDownIcon, CheckIcon, PauseIcon, PlayIcon, RefreshCcwIcon, Cancel01Icon,
} from '@hugeicons/core-free-icons'
import FileTile from './FileTile.vue'
import { rangeText, whenText } from '../lib/format'

const props = defineProps({
  item: { type: Object, required: true },
  reorder: Boolean,
  selectable: Boolean,
  selected: Boolean,
  // Rows that arrive after the screen is up slide in; the ones already there at launch do not.
  enter: Boolean,
})
const emit = defineEmits(['open', 'primary', 'up', 'down', 'toggle', 'cancel'])

// In selection mode a tap picks the row instead of opening it.
function tap() { emit(props.selectable ? 'toggle' : 'open') }

const STATE_TILE = { downloading: 'run', retrying: 'hold', paused: 'hold', error: 'fail', completed: 'done' }
const tileState = computed(() => STATE_TILE[props.item.status] || '')

const sub = computed(() => {
  const i = props.item
  switch (i.status) {
    case 'downloading': {
      const parts = []
      if (i.totalText) parts.push(rangeText(i.downloadedText, i.totalText))
      if (i.speed) parts.push(i.speed)
      if (i.progress) parts.push(`${i.progress}%`)
      return parts.join(' · ') || 'Starting…'
    }
    case 'queued': return 'Queued · waiting for a free slot'
    case 'retrying': return `Network hiccup · auto-retry ${props.item.retries + 1} of 3`
    case 'paused': {
      const parts = []
      if (i.progress) parts.push(`Paused at ${i.progress}%`)
      if (i.totalText) parts.push(rangeText(i.downloadedText, i.totalText))
      return parts.join(' · ') || 'Paused'
    }
    case 'error': return (i.error || 'Failed').replace(/^ERROR:\s*/i, '')
    case 'completed': {
      const when = i.finishedAt ? `finished ${whenText(i.finishedAt)}` : ''
      return [i.totalText, when].filter(Boolean).join(' · ')
    }
    default: return ''
  }
})

// Right rail: percentage only on finished rows; unfinished rows trade it for their controls.
const pcText = computed(() => {
  const s = props.item.status
  if (s === 'completed') return 'done'
  return `${props.item.progress}%`
})
const pcClass = computed(() => ({ completed: 'good' }[props.item.status] || ''))

// Pause, resume or retry — the one primary control on a live row.
const LIVE = ['downloading', 'retrying', 'queued', 'paused', 'error']
const primaryIcon = computed(() => {
  const s = props.item.status
  if (s === 'downloading' || s === 'retrying') return PauseIcon
  if (s === 'error') return RefreshCcwIcon
  return PlayIcon
})
const primaryLabel = computed(() => ({
  downloading: 'Pause download', retrying: 'Pause retrying download', queued: 'Start now',
  paused: 'Resume download', error: 'Retry download',
}[props.item.status] || 'Start download'))

const bar = computed(() => {
  const s = props.item.status
  if (s === 'downloading') return { pct: props.item.progress, cls: '' }
  if (s === 'paused' || s === 'retrying') return { pct: props.item.progress, cls: 'am' }
  if (s === 'completed') return { pct: 100, cls: 'gr' }
  return null
})
</script>

<template>
  <div class="row" :class="{ picked: selected, enter }" role="link" tabindex="0" @click="tap" @keydown.enter="tap">
    <FileTile :name="item.name" :audio-only="item.audioOnly" :source="item.kind" :state="tileState" />

    <div class="row-body">
      <div class="row-name">{{ item.name }}</div>
      <div class="row-sub" :class="{ bad: item.status === 'error' }">{{ sub }}</div>
      <div v-if="bar && !selectable" class="mini"><i :class="bar.cls" :style="{ width: bar.pct + '%' }" /></div>
    </div>

    <div class="row-end" @click.stop>
      <span v-if="selectable" class="check" :class="{ on: selected }">
        <HugeiconsIcon v-if="selected" :icon="CheckIcon" :size="14" :stroke-width="3" />
      </span>

      <template v-else>
        <div v-if="reorder" class="reorder">
          <button aria-label="Move up" @click="emit('up')"><HugeiconsIcon :icon="ChevronUpIcon" :size="15" :stroke-width="2.4" /></button>
          <button aria-label="Move down" @click="emit('down')"><HugeiconsIcon :icon="ChevronDownIcon" :size="15" :stroke-width="2.4" /></button>
        </div>

        <template v-if="LIVE.includes(item.status)">
          <button class="row-act" :aria-label="primaryLabel" @click="emit('primary')">
            <HugeiconsIcon :icon="primaryIcon" :size="15" :stroke-width="2.4" />
          </button>
          <button class="row-act stop" :aria-label="`Cancel and remove ${item.name}`" @click="emit('cancel')">
            <HugeiconsIcon :icon="Cancel01Icon" :size="15" :stroke-width="2.4" />
          </button>
        </template>

        <span v-else class="pc" :class="pcClass">{{ pcText }}</span>
      </template>
    </div>
  </div>
</template>
