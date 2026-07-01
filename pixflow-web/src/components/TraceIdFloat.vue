<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const traceId = computed(() => ui.floatingTraceId ?? '—')

async function copy(): Promise<void> {
  if (!ui.floatingTraceId) {
    ElMessage.info('暂无 traceId')
    return
  }
  try {
    await navigator.clipboard.writeText(ui.floatingTraceId)
    ElMessage.success('traceId 已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}
</script>

<template>
  <div class="trace-float" v-if="ui.floatingTraceId" @click="copy" title="点击复制">
    <span class="label">traceId</span>
    <span class="value">{{ traceId }}</span>
  </div>
</template>

<style scoped>
.trace-float {
  position: fixed;
  right: 12px;
  bottom: 12px;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.75);
  color: white;
  padding: 6px 10px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
  cursor: pointer;
  display: flex;
  gap: 6px;
  align-items: center;
}
.label { color: rgba(255, 255, 255, 0.6); }
.value { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>