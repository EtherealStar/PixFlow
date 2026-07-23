<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppShell from '@/components/layout/AppShell.vue'
import LeftPanel from '@/components/layout/LeftPanel.vue'
import RightPanel from '@/components/layout/RightPanel.vue'
import NetworkBanner from '@/components/layout/NetworkBanner.vue'
import AppToastProvider from '@/components/ui/AppToastProvider.vue'
import { useUiStore } from '@/stores/ui'

/**
 * 应用根。
 *
 * 装配（frontend/shell-routing-auth.md）：
 * - standalone 路由（/login）：全屏渲染，不套三栏
 * - 业务路由：AppShell 三栏（左导航 / 主内容 / 右栏全局 Activity）+ ToastProvider
 *
 * trace ID 只作为失败操作的可复制错误编号出现，无全局 trace 组件。
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
