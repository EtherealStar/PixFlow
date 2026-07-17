<script setup lang="ts">
import { computed } from 'vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppProgressBar from '@/components/ui/AppProgressBar.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import type { PackageDetail } from '@/types/upload'

/**
 * PackageExtractionProgress — 素材包解压进度卡（web.md §十三 / §7.3）
 *
 * - 进度只由服务端实际返回的 imageCount / extractedCount 推导
 * - status 走 tone 配色：uploaded=muted / extracting=warning / ready=success / partial=warning / failed=danger
 */
const props = defineProps<{
  pkg: PackageDetail
}>()

const percent = computed(() => {
  const total = props.pkg.imageCount ?? 0
  const extracted = props.pkg.extractedCount ?? 0
  if (total <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((extracted / total) * 100)))
})

const badge = computed(() => {
  switch (props.pkg.status) {
    case 'UPLOADED': return { tone: 'muted' as const, label: '等待解压' }
    case 'EXTRACTING': return { tone: 'warning' as const, label: '解压中' }
    case 'READY': return { tone: 'success' as const, label: '就绪' }
    case 'PARTIAL': return { tone: 'warning' as const, label: '部分完成' }
    case 'FAILED': return { tone: 'danger' as const, label: '失败' }
  }
})
</script>

<template>
  <AppCard padding="md">
    <div class="flex items-start justify-between gap-3 mb-2">
      <div class="min-w-0 flex-1">
        <div
          class="text-sm font-medium text-fg-primary truncate"
          :title="pkg.name"
        >
          {{ pkg.name }}
        </div>
        <div class="text-xs text-fg-muted">
          已解压 {{ pkg.extractedCount ?? 0 }} / {{ pkg.imageCount ?? 0 }} 张
        </div>
      </div>
      <AppBadge
        :tone="badge.tone"
        style="solid"
      >
        {{ badge.label }}
      </AppBadge>
    </div>
    <AppProgressBar
      :percent="percent"
      :tone="pkg.status === 'FAILED' ? 'danger' : pkg.status === 'READY' ? 'success' : 'accent'"
    />
  </AppCard>
</template>
