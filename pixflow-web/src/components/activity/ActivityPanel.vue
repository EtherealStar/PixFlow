<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ActivityView } from '@/api/activities'
import { useActivitiesStore } from '@/stores/activities'
import { useToastStore } from '@/stores/toast'
import ActivityCard from './ActivityCard.vue'
import AppEmptyState from '@/components/ui/AppEmptyState.vue'
import AppSkeleton from '@/components/ui/AppSkeleton.vue'
import IconUpload from '@/components/icons/IconUpload.vue'

/**
 * ActivityPanel — 全局 Activity 列表（frontend/tasks.md）
 *
 * - 首次加载用骨架屏，不用居中 spinner
 * - 空态教会用户这里会出现什么
 */
const activities = useActivitiesStore()
const toast = useToastStore()
const busyIds = ref(new Set<string>())

const records = computed(() => activities.orderedItems)

async function run(activity: ActivityView, command: 'cancel' | 'retry' | 'clear'): Promise<void> {
  if (busyIds.value.has(activity.activityId)) return
  busyIds.value = new Set(busyIds.value).add(activity.activityId)
  try {
    await activities.runCommand(activity.activityId, command)
  } catch (error: unknown) {
    toast.push({ variant: 'danger', message: error instanceof Error ? error.message : '操作失败' })
  } finally {
    const next = new Set(busyIds.value)
    next.delete(activity.activityId)
    busyIds.value = next
  }
}
</script>

<template>
  <div class="flex h-full flex-col">
    <div
      v-if="activities.loading && records.length === 0"
      class="flex flex-col gap-2"
      aria-label="正在加载活动"
    >
      <AppSkeleton
        v-for="i in 3"
        :key="i"
        rounded="lg"
        height="88px"
      />
    </div>
    <AppEmptyState
      v-else-if="records.length === 0"
      :icon="IconUpload"
      title="暂无活动"
      description="上传素材包或发起图片处理后，进度会显示在这里"
    />
    <div
      v-else
      class="flex flex-col gap-2"
    >
      <ActivityCard
        v-for="activity in records"
        :key="activity.activityId"
        :activity="activity"
        :busy="busyIds.has(activity.activityId)"
        @cancel="run(activity, 'cancel')"
        @retry="run(activity, 'retry')"
        @clear="run(activity, 'clear')"
      />
    </div>
  </div>
</template>
