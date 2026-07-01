<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppShell from '@/components/layout/AppShell.vue'
import LeftPanel from '@/components/layout/LeftPanel.vue'
import RightPanel from '@/components/layout/RightPanel.vue'
import NetworkBanner from '@/components/layout/NetworkBanner.vue'
import TraceIdFloat from '@/components/ui/TraceIdFloat.vue'
import AppToastProvider from '@/components/ui/AppToastProvider.vue'
import { useUiStore } from '@/stores/ui'

/**
 * 应用根。
 *
 * R6 装配（web.md §十一）：
 * - standalone 路由（/login）：全屏渲染，不套三栏
 * - 业务路由：Header + AppShell（三栏）+ 默认 RouterView + TraceIdFloat + ToastProvider
 *
 * 左栏 3 段插槽接入文件树：
 * - upload  → FileTreePanel section="upload"
 * - results → FileTreePanel section="results"
 * - history → HistoryTree
 */
const route = useRoute()
const ui = useUiStore()

const isOnline = ref(navigator.onLine)

function onOnline(): void { isOnline.value = true }
function onOffline(): void { isOnline.value = false }

onMounted(() => {
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
  ui.networkOnline = isOnline.value
})
onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
})

const isStandalone = computed(() => !!route.meta?.standalone)
const isLogin = computed(() => route.name === 'login')
</script>

<template>
  <template v-if="isStandalone || isLogin">
    <NetworkBanner v-if="!isOnline" />
    <RouterView :key="route.path" />
    <TraceIdFloat />
    <AppToastProvider />
  </template>
  <template v-else>
    <div class="app-root">
      <NetworkBanner v-if="!isOnline" />
      <AppShell>
        <template #left>
          <LeftPanel />
        </template>
        <RouterView :key="route.path" />
        <template #right>
          <RightPanel />
        </template>
      </AppShell>
    </div>
    <TraceIdFloat />
    <AppToastProvider />
  </template>
</template>

<style>
.app-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100vw;
  overflow: hidden;
}
</style>