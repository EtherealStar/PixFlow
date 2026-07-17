<script setup lang="ts">
import { computed } from 'vue'
import { useTasksStore } from '@/stores/tasks'
import type { TaskState } from '@/runtime/useTask'
import AppAccordion from '@/components/ui/AppAccordion.vue'
import AppAccordionItem from '@/components/ui/AppAccordionItem.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppEmptyState from '@/components/ui/AppEmptyState.vue'
import TaskCard from '@/components/tasks/TaskCard.vue'

/**
 * TaskPanel — 右栏任务面板（web.md §十）
 *
 * 4 组手风琴：进行中 / 已完成 / 失败 / 已取消。
 * 默认展开第一组。
 */
defineProps<{
  selectedTaskId?: string | null
}>()

const emit = defineEmits<{
  select: [taskId: string]
}>()

const tasks = useTasksStore()

interface Groups {
  inProgress: TaskState[]
  completed: TaskState[]
  failed: TaskState[]
  cancelled: TaskState[]
}

const groups = computed<Groups>(() => {
  const acc: Groups = { inProgress: [], completed: [], failed: [], cancelled: [] }
  for (const t of tasks.items.values()) {
    if (t.phase === 'queued' || t.phase === 'running') acc.inProgress.push(t)
    else if (t.phase === 'completed') acc.completed.push(t)
    else if (t.phase === 'failed' || t.phase === 'partial') acc.failed.push(t)
    else if (t.phase === 'cancelled') acc.cancelled.push(t)
  }
  return acc
})

const isAllEmpty = computed(() =>
  groups.value.inProgress.length === 0 &&
  groups.value.completed.length === 0 &&
  groups.value.failed.length === 0 &&
  groups.value.cancelled.length === 0
)
</script>

<template>
  <div class="task-panel flex flex-col h-full overflow-y-auto">
    <header class="task-panel-header px-4 py-3 border-b border-border">
      <h3 class="text-sm font-medium text-fg-primary">
        任务面板
      </h3>
      <p class="text-xs text-fg-muted">
        实时跟踪上传 / 处理任务进度
      </p>
    </header>

    <AppEmptyState
      v-if="isAllEmpty"
      title="暂无任务"
      description="上传素材或启动处理后会在这里显示"
    />

    <AppAccordion
      v-else
      :default-value="['in-progress']"
      type="multiple"
      class="border-t-0"
    >
      <!-- 进行中 -->
      <AppAccordionItem value="in-progress">
        <template #trigger>
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium">进行中</span>
            <AppBadge
              tone="info"
              style="soft"
            >
              {{ groups.inProgress.length }}
            </AppBadge>
          </div>
        </template>
        <div class="flex flex-col gap-2">
          <TaskCard
            v-for="t in groups.inProgress"
            :key="t.taskId"
            :state="t"
            :selected="selectedTaskId === t.taskId"
            @select="(id) => emit('select', id)"
          />
          <div
            v-if="groups.inProgress.length === 0"
            class="text-xs text-fg-muted py-2"
          >
            暂无进行中的任务
          </div>
        </div>
      </AppAccordionItem>

      <!-- 已完成 -->
      <AppAccordionItem value="completed">
        <template #trigger>
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium">已完成</span>
            <AppBadge
              tone="success"
              style="soft"
            >
              {{ groups.completed.length }}
            </AppBadge>
          </div>
        </template>
        <div class="flex flex-col gap-2">
          <TaskCard
            v-for="t in groups.completed"
            :key="t.taskId"
            :state="t"
            :selected="selectedTaskId === t.taskId"
            @select="(id) => emit('select', id)"
          />
          <div
            v-if="groups.completed.length === 0"
            class="text-xs text-fg-muted py-2"
          >
            暂无已完成的任务
          </div>
        </div>
      </AppAccordionItem>

      <!-- 失败 -->
      <AppAccordionItem value="failed">
        <template #trigger>
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium">失败</span>
            <AppBadge
              tone="danger"
              style="soft"
            >
              {{ groups.failed.length }}
            </AppBadge>
          </div>
        </template>
        <div class="flex flex-col gap-2">
          <TaskCard
            v-for="t in groups.failed"
            :key="t.taskId"
            :state="t"
            :selected="selectedTaskId === t.taskId"
            @select="(id) => emit('select', id)"
          />
          <div
            v-if="groups.failed.length === 0"
            class="text-xs text-fg-muted py-2"
          >
            暂无失败任务
          </div>
        </div>
      </AppAccordionItem>

      <!-- 已取消 -->
      <AppAccordionItem
        v-if="groups.cancelled.length > 0"
        value="cancelled"
      >
        <template #trigger>
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium">已取消</span>
            <AppBadge
              tone="muted"
              style="soft"
            >
              {{ groups.cancelled.length }}
            </AppBadge>
          </div>
        </template>
        <div class="flex flex-col gap-2">
          <TaskCard
            v-for="t in groups.cancelled"
            :key="t.taskId"
            :state="t"
            :selected="selectedTaskId === t.taskId"
            @select="(id) => emit('select', id)"
          />
        </div>
      </AppAccordionItem>
    </AppAccordion>
  </div>
</template>