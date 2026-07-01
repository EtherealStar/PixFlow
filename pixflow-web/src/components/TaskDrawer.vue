<script setup lang="ts">
import { onMounted } from 'vue'
import TaskProgressCard from './TaskProgressCard.vue'
import ResultPreview from './ResultPreview.vue'

const props = defineProps<{
  cid: string
  tid: string
}>()
</script>

<template>
  <aside class="task-drawer">
    <header class="drawer-head">
      <h3>任务详情</h3>
    </header>
    <section class="task-section">
      <TaskProgressCard :task-id="tid" :conversation-id="cid" />
    </section>
    <section class="result-section">
      <ResultPreview :task-id="tid" />
    </section>
  </aside>
</template>

<style scoped>
.task-drawer {
  position: fixed;
  top: 0;
  right: 0;
  width: 480px;
  max-width: 90vw;
  height: 100vh;
  background: var(--color-bg);
  border-left: 1px solid var(--color-border);
  box-shadow: -2px 0 12px rgba(0, 0, 0, 0.06);
  display: flex;
  flex-direction: column;
  z-index: 100;
}
.drawer-head {
  height: var(--header-height);
  display: flex;
  align-items: center;
  padding: 0 16px;
  border-bottom: 1px solid var(--color-border);
}
.drawer-head h3 { margin: 0; font-size: 14px; }
.task-section { padding: 12px; border-bottom: 1px solid var(--color-bg-mute); }
.result-section { flex: 1; overflow-y: auto; }
</style>