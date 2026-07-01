<script setup lang="ts">
import { computed } from 'vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppProgressBar from '@/components/ui/AppProgressBar.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import IconTrash from '@/components/icons/IconTrash.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import type { UploadJobState } from '@/types/upload'

/**
 * UploadJobCard — 上传任务卡片（web.md §十三 / §7.3）
 *
 * - 整文件 / 分片上传通用卡片
 * - hash + chunk 进度条分别展示
 */
const props = defineProps<{
  job: UploadJobState
}>()

const emit = defineEmits<{
  cancel: [jobId: string]
  retry: [jobId: string]
}>()

const percent = computed(() => {
  const p = props.job.progress
  if (!p || p.total <= 0) return 0
  return Math.round((p.uploaded / p.total) * 100)
})

const phaseLabel = computed(() => {
  switch (props.job.phase) {
    case 'idle': return '待处理'
    case 'hashing': return '计算哈希中'
    case 'initing': return '协商上传'
    case 'uploading': return '上传中'
    case 'completing': return '合并中'
    case 'done': return '完成'
    case 'error': return '失败'
    case 'cancelled': return '已取消'
  }
})

const badgeTone = computed(() => {
  switch (props.job.phase) {
    case 'done': return 'success' as const
    case 'error': return 'danger' as const
    case 'cancelled': return 'muted' as const
    case 'uploading':
    case 'hashing':
    case 'initing':
    case 'completing': return 'info' as const
    default: return 'muted' as const
  }
})

const canRetry = computed(() => props.job.phase === 'error' || props.job.phase === 'cancelled')
const canCancel = computed(() => props.job.phase === 'idle' || props.job.phase === 'hashing' || props.job.phase === 'initing' || props.job.phase === 'uploading')

function fmtSize(n: number): string {
  if (n <= 0) return '0 B'
  const k = 1024
  const units = ['B', 'KB', 'MB', 'GB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(n) / Math.log(k)))
  return `${(n / Math.pow(k, i)).toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}
</script>

<template>
  <AppCard padding="md">
    <div class="flex items-start justify-between gap-3 mb-2">
      <div class="min-w-0 flex-1">
        <div class="text-sm font-medium text-fg-primary truncate" :title="job.filename">
          {{ job.filename }}
        </div>
        <div class="text-xs text-fg-muted mt-0.5">
          {{ fmtSize(job.size) }} · {{ job.uploadedChunks }}/{{ job.totalChunks }} 分片
          <span v-if="job.fileHash" class="font-mono ml-2">#{{ job.fileHash.slice(0, 8) }}</span>
        </div>
      </div>
      <AppBadge :tone="badgeTone" style="solid">{{ phaseLabel }}</AppBadge>
    </div>

    <AppProgressBar :percent="percent" :tone="job.phase === 'error' ? 'danger' : job.phase === 'done' ? 'success' : 'accent'" />

    <p v-if="job.error" class="mt-2 text-xs text-danger">错误：{{ job.error.message }}</p>

    <div class="flex gap-2 justify-end mt-3">
      <AppButton v-if="canCancel" size="sm" variant="ghost" @click="emit('cancel', job.jobId)">
        <IconTrash :size="14" class="mr-1" />
        取消
      </AppButton>
      <AppButton v-if="canRetry" size="sm" variant="primary" @click="emit('retry', job.jobId)">
        <IconRefresh :size="14" class="mr-1" />
        重试
      </AppButton>
    </div>
  </AppCard>
</template>