<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import TaskDetailHeader from '@/components/tasks/TaskDetailHeader.vue'
import ResultPreview from '@/components/tasks/ResultPreview.vue'
import ImagePreviewDialog from '@/components/files/ImagePreviewDialog.vue'
import { useTasksStore } from '@/stores/tasks'
import { createTask } from '@/runtime/useTask'
import { useToastStore } from '@/stores/toast'
import { cancelTask as cancelTaskApi } from '@/api/tasks'
import type { ImageItem } from '@/components/files/ImageGrid.vue'

/**
 * 任务详情页（/tasks/:tid），web.md §十
 *
 * - 顶部 TaskDetailHeader：phase / 时间 / 操作
 * - 主体 ResultPreview：产物网格
 * - 创建 task 实例建立 WS 订阅
 */
const route = useRoute()
const router = useRouter()
const tasks = useTasksStore()
const toast = useToastStore()

const tid = computed(() => String(route.params.tid ?? ''))
const selectedIds = ref<string[]>([])

// 仅当 store 中没有时，新建一个
const taskHandle = computed(() => {
  if (!tid.value) return null
  const existing = tasks.get(tid.value)
  if (existing) return null
  return createTask({ taskId: tid.value, conversationId: route.query.cid as string ?? '' })
})

const state = computed(() => tasks.get(tid.value) ?? taskHandle?.value?.state.value ?? null)

const results = ref<ImageItem[]>([])

const previewOpen = ref(false)
const previewItem = ref<ImageItem | null>(null)

onMounted(() => {
  // R6 阶段：根据 taskId 调 /api/tasks/{tid}/results 拉结果列表
  // 此处给空占位
  results.value = []
})

async function onCancel(): Promise<void> {
  try {
    await cancelTaskApi(tid.value)
    toast.push({ variant: 'info', message: '已请求取消' })
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    toast.push({ variant: 'danger', message: msg })
  }
}

function onRetry(): void {
  toast.push({ variant: 'info', message: '重试功能即将上线' })
}

function onRefresh(): void {
  // R6 阶段：重新拉取 /api/tasks/{tid} + /api/tasks/{tid}/results
  toast.push({ variant: 'info', message: '已刷新' })
}

function onOpenExternal(): void {
  window.open(`/tasks/${tid.value}`, '_blank')
}

function onPreview(item: ImageItem): void {
  previewItem.value = item
  previewOpen.value = true
}

function onDownload(item: ImageItem): void {
  if (!item.src) return
  const a = document.createElement('a')
  a.href = item.src
  a.download = item.filename
  document.body.appendChild(a)
  a.click()
  a.remove()
}

function onOpenItemExternal(item: ImageItem): void {
  if (item.src) window.open(item.src, '_blank')
}

function goBack(): void {
  void router.back()
}
</script>

<template>
  <section class="task-detail-page flex flex-col h-full bg-bg-page" v-if="state">
    <TaskDetailHeader
      :state="state"
      @refresh="onRefresh"
      @cancel="onCancel"
      @retry="onRetry"
      @open-external="onOpenExternal"
    />
    <div class="flex-1 overflow-y-auto">
      <ResultPreview
        :state="state"
        :results="results"
        :selected-ids="selectedIds"
        @update:selected-ids="(ids: string[]) => (selectedIds = ids)"
        @preview="onPreview"
        @download="onDownload"
        @open-external="onOpenItemExternal"
      />
    </div>
    <footer class="border-t border-border bg-bg-panel px-6 py-3 flex justify-end">
      <button class="text-sm text-fg-secondary hover:text-fg-primary" @click="goBack">返回</button>
    </footer>

    <ImagePreviewDialog
      v-if="previewItem"
      :open="previewOpen"
      :src="previewItem.src"
      :alt="previewItem.alt"
      :filename="previewItem.filename"
      :size="previewItem.size"
      @update:open="(v: boolean) => (previewOpen = v)"
      @download="onDownload(previewItem)"
      @open-external="onOpenItemExternal(previewItem)"
    />
  </section>

  <section v-else class="flex items-center justify-center h-full text-fg-muted">
    任务不存在或尚未同步
  </section>
</template>