<script setup>
import { watch } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { HugeiconsIcon } from '@hugeicons/vue'
import { GearsIcon, Cancel01Icon } from '@hugeicons/core-free-icons'
import { SECTIONS } from '../lib/sections'
import { useDownloadsStore } from '../stores/downloads'
import { drawerOpen, closeDrawer } from '../lib/drawer'

const route = useRoute()
const { items } = storeToRefs(useDownloadsStore())

// A tap anywhere in the drawer navigates, so it shuts behind you.
watch(() => route.fullPath, closeDrawer)
</script>

<template>
  <div v-if="drawerOpen" class="drawer-scrim" aria-hidden="true" @click="closeDrawer" />

  <aside class="drawer" :class="{ open: drawerOpen }" :aria-hidden="!drawerOpen"
    :inert="!drawerOpen || undefined" aria-label="Navigation">
    <div class="drawer-head">
      <span class="drawer-brand">
        <svg class="drawer-mark" viewBox="0 0 108 108" aria-hidden="true">
          <path d="M67.5,30.62 A27,27 0 1 1 40.5,30.62" fill="none" stroke="currentColor" stroke-width="9" stroke-linecap="round" />
          <path d="M59.7,35.5 L37.7,61.9 L57.5,61.9 L55.3,79.5 L77.3,53.1 L57.5,53.1 Z" fill="currentColor" opacity="0.35" />
          <path d="M56.2,32 L34.2,58.4 L54,58.4 L51.8,76 L73.8,49.6 L54,49.6 Z" fill="#A8E6CF" stroke="currentColor" stroke-width="5" stroke-linejoin="round" />
        </svg>
        Downswing
      </span>
      <button class="icon-btn" aria-label="Close menu" @click="closeDrawer">
        <HugeiconsIcon :icon="Cancel01Icon" :size="20" :stroke-width="2.4" />
      </button>
    </div>

    <nav class="side-links">
      <RouterLink v-for="s in SECTIONS" :key="s.id" :to="s.to" :class="{ on: route.meta.section === s.id }">
        <HugeiconsIcon :icon="s.icon" :size="19" :stroke-width="2" />
        <span class="side-label">{{ s.label }}</span>
        <span v-if="s.id === 'downloading' && items.filter((i) => i.status === 'downloading').length"
          class="side-cnt num">{{ items.filter((i) => i.status === 'downloading').length }}</span>
      </RouterLink>
    </nav>

    <div class="side-foot">
      <RouterLink to="/settings" class="side-act" :class="{ on: route.path === '/settings' }">
        <HugeiconsIcon :icon="GearsIcon" :size="18" :stroke-width="2" />
        <span class="side-label">Settings</span>
      </RouterLink>
    </div>
  </aside>
</template>
