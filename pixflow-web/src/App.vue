<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppLayout from './components/AppLayout.vue'
import TraceIdFloat from './components/TraceIdFloat.vue'
import NetworkBanner from './components/NetworkBanner.vue'
import { useUiStore } from './stores/ui'

const route = useRoute()
const ui = useUiStore()
const isOnline = ref(navigator.onLine)

const onOnline = () => (isOnline.value = true)
const onOffline = () => (isOnline.value = false)

onMounted(() => {
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
})
onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
})
</script>

<template>
  <AppLayout v-if="!route.meta.standalone" :sidebar-open="ui.sidebarOpen">
    <NetworkBanner v-if="!isOnline" />
    <RouterView />
  </AppLayout>
  <RouterView v-else />
  <TraceIdFloat />
</template>