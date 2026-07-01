<script setup lang="ts">
/**
 * AppShell — 三栏布局容器（web.md §六）
 *
 * 视觉：
 * - 高度 calc(100vh - 56px)（Header 由 App.vue 单独渲染）
 * - 横向 flex
 * - 三栏背景同 bg-page（避免"白夹灰"割裂）
 *
 * 槽位：
 * - left: 左栏（默认 280px，可折叠为 8px 把手）
 * - default: MainView（占满剩余宽度）
 * - right: 右栏（默认收起 8px 把手，可展开为 360px）
 */
</script>

<template>
  <div class="app-shell">
    <div class="app-shell-left">
      <slot name="left" />
    </div>
    <main class="app-shell-main">
      <slot />
    </main>
    <div class="app-shell-right">
      <slot name="right" />
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  background: var(--bg-page);
  overflow: hidden;
}
.app-shell-left {
  flex-shrink: 0;
  display: flex;
}
.app-shell-main {
  flex: 1 1 0;
  min-width: 0;
  overflow: auto;
  background: var(--bg-page);
}
.app-shell-right {
  flex-shrink: 0;
  display: flex;
}
</style>