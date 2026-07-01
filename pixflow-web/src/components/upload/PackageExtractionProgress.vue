<script setup lang="ts">
import { computed } from 'vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppProgressBar from '@/components/ui/AppProgressBar.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import type { PackageDetail } from '@/types/upload'

/**
 * PackageExtractionProgress — 素材包解压进度卡（web.md §十三 / §7.3）
 *
 * - 展示 scanStage / scanProgress / 处理进度（extractProgress）
 * - phase 走 tone 配色：pending=muted / scanning=info / extracting=warning / ready=success / error=danger
 */
const props = defineProps<{
  pkg: PackageDetail
}>()

const percent = computed(() => {
  const ep = props.pkg.extractProgress
  if (!ep || ep.total <= 0) return 0
  return Math.round((ep.done / ep.total) * 100)
})

const badge = computed(() => {
  switch (props.pkg.status) {
    case 'PENDING': return { tone: 'muted' as const, label: '等待中' }
    case 'UPLOADING': return { tone: 'info' as const, label: '上传中' }
    case 'SCANNING': return { tone: 'info' as const, label: '扫描中' }
    case 'EXTRACTING': return { tone: 'warning' as const, label: '解压中' }
    case 'READY': return { tone: 'success' as const, label: '就绪' }
    case 'ERROR': return { tone: 'danger' as const, label: '失败' }
    default: return { tone: 'muted' as const, label: '未知' }
  }
})

void computed(() => props.pkg.scanProgress)
</script>

<template>
  <AppCard padding="md">
    <div class="flex items-start justify-between gap-3 mb-2">
      <div class="min-w-0 flex-1">
        <div class="text-sm font-medium text-fg-primary truncate" :title="pkg.name">{{ pkg.name }}</div>
        <div class="text-xs text-fg-muted">
          {{ pkg.imageCount ?? 0 }} 张 · {{ pkg.scanStage ?? '等待' }}
        </div>
      </div>
      <AppBadge :tone="badge.tone" style="solid">{{ badge.label }}</AppBadge>
    </div>
    <AppProgressBar
      :percent="percent"
      :tone="pkg.status === 'ERROR' ? 'danger' : pkg.status === 'READY' ? 'success' : 'accent'"
    />
  </AppCard>
</template>