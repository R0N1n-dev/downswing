<script setup>
import { HugeiconsIcon } from '@hugeicons/vue'
import { Cancel01Icon } from '@hugeicons/core-free-icons'

// The app's one confirm slip. Android's AlertDialog would break the paper-and-ink look and
// Android may suppress native dialogs over a WebView, so confirms are drawn in the shell,
// like the share sheet. Two answers, plus an optional third when a choice is genuinely three-way.
const props = defineProps({
  title: { type: String, required: true },
  note: { type: String, default: '' },
  confirmLabel: { type: String, default: 'Confirm' },
  cancelLabel: { type: String, default: 'Cancel' },
  altLabel: { type: String, default: '' }, // optional middle choice; hidden when empty
  tone: { type: String, default: 'primary' }, // primary | warn | danger
})
const emit = defineEmits(['confirm', 'alt', 'dismiss'])
</script>

<template>
  <div class="sheet-scrim" aria-hidden="true" @click="emit('dismiss')" />
  <div class="sheet confirm-sheet" role="dialog" aria-modal="true" :aria-label="props.title">
    <div class="grab" />

    <div class="sheet-head">
      <span class="sheet-title">{{ props.title }}</span>
      <button class="sheet-x" aria-label="Dismiss" @click="emit('dismiss')">
        <HugeiconsIcon :icon="Cancel01Icon" :size="16" :stroke-width="2.4" />
      </button>
    </div>

    <p v-if="props.note" class="sheet-note">{{ props.note }}</p>

    <button class="btn" :class="props.tone" @click="emit('confirm')">{{ props.confirmLabel }}</button>
    <div class="confirm-acts">
      <button v-if="props.altLabel" class="btn" @click="emit('alt')">{{ props.altLabel }}</button>
      <button class="btn ghost" @click="emit('dismiss')">{{ props.cancelLabel }}</button>
    </div>
  </div>
</template>

<style scoped>
/* The sheet stays paper-and-ink in both themes: it reads as a physical slip over the app. */
.confirm-sheet .btn { height: 44px; }
.confirm-sheet .btn.primary { background: var(--mint); color: #0E0E10; }
.confirm-sheet .btn.warn { background: var(--butter); color: #0E0E10; }
.confirm-sheet .btn.danger { background: var(--blush); color: #0E0E10; }
.confirm-sheet .btn:not(.primary):not(.warn):not(.danger) { background: var(--paper); color: #0E0E10; }
.confirm-acts { display: flex; gap: 10px; }
.confirm-acts .btn { flex: 1; }
.confirm-sheet .sheet-note { text-align: left; line-height: 1.45; }
.sheet-x { width: 34px; height: 34px; flex: none; display: grid; place-items: center;
  border: 2.5px solid var(--ink); border-radius: 10px; background: var(--paper);
  box-shadow: var(--sh-xs); transition: transform 0.07s, box-shadow 0.07s; }
.sheet-x:active { transform: translate(2px, 2px); box-shadow: none; }
</style>
