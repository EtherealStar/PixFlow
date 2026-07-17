<script setup lang="ts">
import { computed } from 'vue'
import AppEmptyState from '@/components/ui/AppEmptyState.vue'
import ImageGrid from '@/components/files/ImageGrid.vue'
import type { GalleryImageItem } from '@/types/files'
import IconImage from '@/components/icons/IconImage.vue'
import IconLoader from '@/components/icons/IconLoader.vue'
import type { TaskState } from '@/runtime/useTask'
import type { TaskResult } from '@/api/tasks'
import { AlertTriangle } from 'lucide-vue-next'

/**
 * ResultPreview — 任务结果预览（web.md §十 / §十四）
 *
 * - 纯展示：把后端 results[] 映射成 GalleryImageItem[] 喂给 ImageGrid
 * - 仅 phase = completed / partial 时才显示结果；其余态显示空状态
 */
const props = defineProps<{
  state: TaskState
  /** 由父组件注入（按任务 id 查后端结果接口） */
  results: GalleryImageItem[]
  failures: TaskResult[]
  selectedIds: string[]
}>()

const emit = defineEmits<{
  'update:selectedIds': [ids: string[]]
  preview: [item: GalleryImageItem]
  download: [item: GalleryImageItem]
  'open-external': [item: GalleryImageItem]
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
      <h3 class="text-sm font-medium text-fg-primary">
        处理结果
      </h3>
      <p class="text-xs text-fg-muted mt-0.5">
        {{ results.length }} 个产物
      </p>
    </header>

    <ImageGrid
      v-if="hasResults"
      :items="results"
      :selected-ids="selectedIds"
      checkable
      @update:selected-ids="(ids: string[]) => emit('update:selectedIds', ids)"
      @preview="(it: GalleryImageItem) => emit('preview', it)"
      @download="(it: GalleryImageItem) => emit('download', it)"
      @open-external="(it: GalleryImageItem) => emit('open-external', it)"
    />

    <div
      v-if="failures.length"
      class="border-t border-border px-6 py-4"
    >
      <h4 class="flex items-center gap-2 text-sm font-medium text-fg-primary">
        <AlertTriangle
          :size="16"
          class="text-warning"
        />
        失败项（{{ failures.length }}）
      </h4>
      <ul class="mt-3 divide-y divide-border border-y border-border">
        <li
          v-for="item in failures"
          :key="item.resultId"
          class="py-3 text-xs"
        >
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span class="font-medium text-fg-primary">{{ item.filename || item.imageId || item.resultId }}</span>
            <span
              v-if="item.failure?.failedTool"
              class="text-fg-muted"
            >{{ item.failure.failedTool }}</span>
            <span class="text-fg-muted">尝试 {{ item.failure?.attemptCount ?? 0 }} 次</span>
          </div>
          <p class="mt-1 text-danger">
            {{ item.failure?.safeMessage || item.errorMsg || item.failure?.code || '处理失败' }}
          </p>
        </li>
      </ul>
    </div>

    <AppEmptyState
      v-if="!hasResults && failures.length === 0 && isTerminal"
      :icon="IconImage"
      title="暂无结果"
      :description="
        state.phase === 'failed' ? '任务失败，请查看上方错误信息' :
        state.phase === 'cancelled' ? '任务已取消' :
        '处理完成但没有产物'
      "
    />

    <AppEmptyState
      v-else-if="!hasResults && failures.length === 0"
      :icon="IconLoader"
      title="处理中"
      description="正在生成结果，请稍候..."
    />
  </section>
</template>
