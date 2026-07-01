<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { useUiStore } from '@/stores/ui'

defineProps<{ sidebarOpen: boolean }>()
const ui = useUiStore()
</script>

<template>
  <div class="layout">
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <header class="sidebar-head">
        <h1>PixFlow</h1>
        <el-button size="small" @click="ui.toggleSidebar()">{{ sidebarOpen ? '收起' : '展开' }}</el-button>
      </header>
      <nav class="nav">
        <RouterLink to="/chat/new" class="nav-item">新建对话</RouterLink>
        <RouterLink to="/packages" class="nav-item">素材包</RouterLink>
        <RouterLink to="/tasks" class="nav-item">任务列表</RouterLink>
        <RouterLink to="/rubrics" class="nav-item">评分 <span class="badge">Wave 6</span></RouterLink>
        <RouterLink to="/settings" class="nav-item">设置 <span class="badge">Wave 6</span></RouterLink>
      </nav>
    </aside>
    <main class="main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
}
.sidebar {
  width: var(--sidebar-width);
  background: var(--color-bg);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width 0.2s;
}
.sidebar:not(.open) {
  width: 56px;
}
.sidebar-head {
  height: var(--header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  border-bottom: 1px solid var(--color-border);
}
.sidebar-head h1 { font-size: 16px; margin: 0; }
.sidebar:not(.open) .sidebar-head h1,
.sidebar:not(.open) .nav-item span,
.sidebar:not(.open) .nav-item { display: none; }
.nav { padding: 8px 0; }
.nav-item {
  display: block;
  padding: 8px 16px;
  color: var(--color-text);
  text-decoration: none;
  font-size: 14px;
}
.nav-item.router-link-active {
  background: var(--color-bg-soft);
  color: var(--color-primary);
}
.badge {
  display: inline-block;
  margin-left: 6px;
  padding: 0 4px;
  font-size: 10px;
  background: var(--color-bg-mute);
  border-radius: 2px;
  color: var(--color-text-mute);
}
.main {
  flex: 1;
  overflow: auto;
  min-width: 0;
}
</style>