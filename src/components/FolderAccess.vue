<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { App } from '@capacitor/app'
import { Downswing } from '../services/downswing'

const props = defineProps({ folder: { type: String, default: '' } })

const access = ref({ supported: false, granted: true })
let handle

async function check() {
  try {
    access.value = await Downswing.hasAllFilesAccess()
  } catch {
    access.value = { supported: false, granted: true }
  }
}

const needed = computed(() => !!props.folder && access.value.supported && !access.value.granted)

onMounted(async () => {
  check()
  // The user grants access in system settings, so re-check when they come back.
  try { handle = await App.addListener('resume', check) } catch { /* browser */ }
})
onBeforeUnmount(() => handle?.remove())
</script>

<template>
  <div v-if="needed" class="access" role="alert">
    <p>Saving to a shared folder needs "All files access". Until you allow it, Downswing saves to its own app folder.</p>
    <button class="btn primary slim" @click="Downswing.requestAllFilesAccess()">Allow access</button>
  </div>
</template>
