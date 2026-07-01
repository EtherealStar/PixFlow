<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { createPackageProgress } from '@/runtime/usePackageProgress'

const props = defineProps<{ packageId: number }>()
const progress = createPackageProgress({ packageId: props.packageId })

onMounted(() => progress.subscribe())
onUnmounted(() => progress.unsubscribe())
</script>

<template>
  <div class="extraction-progress">
    <el-progress
      :percentage="progress.state.value.total > 0 ? Math.round((progress.state.value.extracted / progress.state.value.total) * 100) : 0"
      :status="progress.state.value.status === 'FAILED' ? 'exception' : progress.state.value.status === 'READY' ? 'success' : undefined"
    />
    <div class="stats">
      {{ progress.state.value.extracted }} / {{ progress.state.value.total }} · {{ progress.state.value.status }}
    </div>
  </div>
</template>

<style scoped>
.extraction-progress { padding: 12px 0; }
.stats { font-size: 12px; color: var(--color-text-mute); margin-top: 6px; }
</style>