<script setup lang="ts">
import IconAlertCircle from '@/components/icons/IconAlertCircle.vue'

/**
 * NetworkBanner — 顶部网络断开条幅（web.md §七.2）
 *
 * 视觉：
 * - bg-warning text-fg-inverse
 * - text-sm
 * - 带 IconAlertCircle（纯 SVG，禁 emoji）
 */
</script>

<template>
  <div
    class="network-banner"
    role="alert"
  >
    <IconAlertCircle
      :size="14"
      class="banner-icon"
    />
    <span>网络已断开，正在重连...</span>
  </div>
</template>

<style scoped>
.network-banner {
  background: var(--warning);
  color: var(--fg-inverse);
  padding: 6px 16px;
  font-size: 13px;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
</style>