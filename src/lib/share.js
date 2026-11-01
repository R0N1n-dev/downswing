import { ref } from 'vue'
import { Downswing } from '../services/downswing'
import { isMagnet } from './detect'

// Links handed over by the Android share sheet, waiting for the Add screen. Consumed once.
const pending = ref('')

export function consumeShared() {
  const text = pending.value
  pending.value = ''
  return text
}

// Every shareable token in a shared message: links survive surrounding text.
export function extractLinks(text) {
  return (text || '').split(/\s+/).filter((s) => /^https?:\/\//i.test(s) || isMagnet(s))
}

// Fetch whatever the share sheet delivered. Returns it (or '') so the caller can navigate.
export async function pollShared() {
  try {
    const { text } = await Downswing.getSharedText()
    const links = extractLinks(text)
    if (!links.length) return ''
    pending.value = links.join(' ')
    return pending.value
  } catch {
    return '' // browser preview: there is no Android share sheet
  }
}
