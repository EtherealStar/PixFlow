<script setup lang="ts">
import { computed } from 'vue'
import AppEmptyState from '@/components/ui/AppEmptyState.vue'
import ImageGrid from '@/components/files/ImageGrid.vue'
import type { ImageItem } from '@/components/files/ImageGrid.vue'
import IconImage from '@/components/icons/IconImage.vue'
import type { TaskState } from '@/runtime/useTask'

/**
 * ResultPreview — 任务结果预览（web.md §十 / §十四）
 *
 * - 纯展示：把后端 results[] 映射成 ImageItem[] 喂给 ImageGrid
 * - 仅 phase = completed / partial 时才显示结果；其余态显示空状态
 */
const props = defineProps<{
  state: TaskState
  /** 由父组件注入（按任务 id 查后端结果接口） */
  results: ImageItem[]
  selectedIds: string[]
}>()

const emit = defineEmits<{
  'update:selectedIds': [ids: string[]]
  preview: [item: ImageItem]
  download: [item: ImageItem]
  'open-external': [item: ImageItem]
}>()

const hasResults = computed(() => props.results.length > 0)
const isTerminal = computed(() =>
  props.state.phase === 'completed' ||
  props.state.phase === 'partial' ||
  props.state.phase === 'failed' ||
  props.state.phase === 'cancelled'
)
</script>

<template>
  <section class="result-preview bg-bg-page">
    <header class="px-6 py-4 border-b border-border bg-bg-panel">
      <h3 class="text-sm font-medium text-fg-primary">处理结果</h3>
      <p class="text-xs text-fg-muted mt-0.5">{{ results.length }} 个产物</p>
    </header>

    <ImageGrid
      v-if="hasResults"
      :items="results"
      :selected-ids="selectedIds"
      checkable
      @update:selected-ids="(ids: string[]) => emit('update:selectedIds', ids)"
      @preview="(it: ImageItem) => emit('preview', it)"
      @download="(it: ImageItem) => emit('download', it)"
      @open-external="(it: ImageItem) => emit('open-external', it)"
    />

    <AppEmptyState
      v-else-if="isTerminal"
      :icon="IconImage"
      title="暂无结果"
      :description="
        state.phase === 'failed' ? '任务失败，请查看上方错误信息' :
        state.phase === 'cancelled' ? '任务已取消' :
        '处理完成但没有产物'
      "
    />

    <AppEmptyState
      v-else
      :icon="IconLoader"
      title="处理中"
      description="正在生成结果，请稍候..."
    />
  </section>
</template>