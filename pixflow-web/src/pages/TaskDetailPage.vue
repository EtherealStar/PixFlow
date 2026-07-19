<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskDetailHeader from '@/components/tasks/TaskDetailHeader.vue'
import ResultPreview from '@/components/tasks/ResultPreview.vue'
import ImagePreviewDialog from '@/components/files/ImagePreviewDialog.vue'
import { useTasksStore } from '@/stores/tasks'
import { createTask } from '@/runtime/useTask'
import { useToastStore } from '@/stores/toast'
import { cancelTask as cancelTaskApi, listTaskResults, retryFailedTask, type TaskResult } from '@/api/tasks'
import type { GalleryImageItem } from '@/types/files'
import type { TaskState } from '@/runtime/useTask'

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
const taskHandle = shallowRef<ReturnType<typeof createTask> | null>(null)

const state = computed<TaskState | null>(() =>
  tasks.items.get(tid.value) ?? taskHandle.value?.state.value ?? null
)

const results = ref<GalleryImageItem[]>([])
const failures = ref<TaskResult[]>([])
const loading = ref(false)

const previewOpen = ref(false)
const previewItem = ref<GalleryImageItem | null>(null)

onMounted(async () => {
  await bindTask()
})

watch(tid, () => {
  void bindTask()
})

async function bindTask(): Promise<void> {
  taskHandle.value?.unsubscribeWS()
  results.value = []
  failures.value = []
  taskHandle.value = createTask({
    taskId: tid.value,
    conversationId: String(route.query.cid ?? '')
  })
  taskHandle.value.subscribeWS()
  await refreshAll()
}

onBeforeUnmount(() => taskHandle.value?.unsubscribeWS())

watch(() => taskHandle.value?.state.value, (next) => {
  if (next) tasks.upsert(next)
}, { deep: true })

async function refreshAll(): Promise<void> {
  if (!tid.value || loading.value) return
  loading.value = true
  try {
    await taskHandle.value?.refresh()
    const page = await listTaskResults(tid.value, { page: 0, size: 100 })
    const records = page.items ?? page.records ?? []
    failures.value = records.filter((item) => item.status === 'FAILED')
    results.value = records
      .filter((item) => item.status === 'SUCCESS' && item.url)
      .map(toImageItem)
  } catch (e) {
    const msg = isApiMessage(e)
    toast.push({ variant: 'danger', message: msg })
  } finally {
    loading.value = false
  }
}

async function onCancel(): Promise<void> {
  try {
    await cancelTaskApi(String(route.query.cid ?? ''), tid.value)
    toast.push({ variant: 'info', message: '已请求取消' })
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    toast.push({ variant: 'danger', message: msg })
  }
}

async function onRetry(): Promise<void> {
  try {
    const derived = await retryFailedTask(tid.value)
    tasks.addQueued(derived.taskId, derived.retryOfTaskId)
    await router.push({ path: `/tasks/${derived.taskId}`, query: route.query })
  } catch (e) {
    toast.push({ variant: 'danger', message: isApiMessage(e) })
  }
}

function onRefresh(): void {
  void refreshAll()
}

function onOpenExternal(): void {
  window.open(`/tasks/${tid.value}`, '_blank')
}

function onPreview(item: GalleryImageItem): void {
  previewItem.value = item
  previewOpen.value = true
}

function onDownload(item: GalleryImageItem): void {
  if (!item.src) return
  const a = document.createElement('a')
  a.href = item.src
  a.download = item.filename
  document.body.appendChild(a)
  a.click()
  a.remove()
}

function onOpenItemExternal(item: GalleryImageItem): void {
  if (item.src) window.open(item.src, '_blank')
}

function goBack(): void {
  void router.back()
}

function toImageItem(result: TaskResult): GalleryImageItem {
  return {
    id: result.resultId,
    src: result.url ?? '',
    alt: result.displayName ?? result.filename ?? '任务结果',
    filename: result.displayName ?? result.filename ?? `result-${result.resultId}`,
    size: result.size === null || result.size === undefined ? undefined : formatBytes(result.size)
  }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function isApiMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
</script>

<template>
  <section
    v-if="state"
    class="task-detail-page flex flex-col h-full bg-bg-page"
  >
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
        :failures="failures"
        :selected-ids="selectedIds"
        @update:selected-ids="(ids: string[]) => (selectedIds = ids)"
        @preview="onPreview"
        @download="onDownload"
        @open-external="onOpenItemExternal"
      />
    </div>
    <footer class="border-t border-border bg-bg-panel px-6 py-3 flex justify-end">
      <button
        class="text-sm text-fg-secondary hover:text-fg-primary"
        @click="goBack"
      >
        返回
      </button>
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

  <section
    v-else
    class="flex items-center justify-center h-full text-fg-muted"
  >
    任务不存在或尚未同步
  </section>
</template>
