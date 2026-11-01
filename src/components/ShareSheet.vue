<script setup>
import { computed } from 'vue'
import { HugeiconsIcon } from '@hugeicons/vue'
import { Cancel01Icon, BoltIcon, SlidersHorizontalIcon, Copy01Icon, Link01Icon } from '@hugeicons/core-free-icons'
import { detectKind, isMagnet, KIND_LABEL } from '../lib/detect'

// The Android share sheet hands a link over: this is the action sheet that catches it.
const props = defineProps({ links: { type: Array, required: true } })
const emit = defineEmits(['download', 'options', 'copy', 'dismiss'])

const shown = computed(() => props.links.slice(0, 3))
const extra = computed(() => props.links.length - shown.value.length)

const hostOf = (url) => {
  if (isMagnet(url)) return 'Magnet link'
  try { return new URL(url).hostname.replace(/^www\./, '') } catch { return url }
}
const kindOf = (url) => {
  const k = detectKind(url)
  return k ? KIND_LABEL[k] : 'Link'
}
</script>

<template>
  <div class="sheet-scrim" aria-hidden="true" @click="emit('dismiss')" />
  <div class="sheet" role="dialog" aria-modal="true" aria-label="Link shared to Downswing">
    <div class="grab" />

    <div class="sheet-head">
      <span class="sheet-title">Link shared</span>
      <button class="sheet-x" aria-label="Dismiss" @click="emit('dismiss')">
        <HugeiconsIcon :icon="Cancel01Icon" :size="16" :stroke-width="2.4" />
      </button>
    </div>

    <div v-for="link in shown" :key="link" class="sheet-link">
      <HugeiconsIcon :icon="Link01Icon" :size="18" :stroke-width="2.2" />
      <span class="host">{{ hostOf(link) }}</span>
      <span class="kind">{{ kindOf(link) }}</span>
    </div>
    <p v-if="extra > 0" class="sheet-note">+{{ extra }} more link{{ extra > 1 ? 's' : '' }} — all of them will be added.</p>

    <button class="btn primary" @click="emit('download')">
      <HugeiconsIcon :icon="BoltIcon" :size="16" :stroke-width="2.4" /> Download now
    </button>

    <div class="sheet-acts">
      <button class="btn" @click="emit('options')">
        <HugeiconsIcon :icon="SlidersHorizontalIcon" :size="16" :stroke-width="2.2" /> Options
      </button>
      <button class="btn" @click="emit('copy')">
        <HugeiconsIcon :icon="Copy01Icon" :size="16" :stroke-width="2.2" /> Copy
      </button>
    </div>

    <p class="sheet-note">Caught from Android's share sheet — pick what to do with it.</p>
  </div>
</template>

<style scoped>
/* The sheet stays paper-and-ink in both themes: it reads as a physical slip over the app. */
.sheet .btn { height: 44px; }
.sheet .btn.primary { background: var(--mint); color: #0E0E10; }
.sheet .btn:not(.primary) { background: var(--paper); color: #0E0E10; }
.sheet-acts { display: flex; gap: 10px; }
.sheet-acts .btn { flex: 1; }
.sheet-acts .btn:last-child { background: var(--sky); }
.sheet-x { width: 34px; height: 34px; flex: none; display: grid; place-items: center;
  border: 2.5px solid var(--ink); border-radius: 10px; background: var(--paper);
  box-shadow: var(--sh-xs); transition: transform 0.07s, box-shadow 0.07s; }
.sheet-x:active { transform: translate(2px, 2px); box-shadow: none; }
.sheet-link .host { color: var(--ink); }
</style>
