<script setup lang="ts">
import IconAlertCircle from '@/components/icons/IconAlertCircle.vue'

/**
 * NetworkBanner — 顶部网络断开条幅（web.md §七.2）
 *
 * 视觉：web.md 原文为 bg-warning + text-fg-inverse，但白字在 #f59e0b 上
 * 对比度约 2:1，不达 WCAG 2.1 AA（DESIGN.md 硬性要求）。改用 warning-soft
 * 底 + fg-primary 文（对比度约 17:1），状态色由底色承担、文本负责表意，
 * 与 QueuePanel 暂停条、MessageStream 错误条同一模式。
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
  background: var(--warning-soft);
  color: var(--fg-primary);
  border-bottom: 1px solid var(--border);
  padding: 6px 16px;
  font-size: 13px;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
</style>
