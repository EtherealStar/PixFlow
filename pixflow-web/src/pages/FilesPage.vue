<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PackageUploader from '@/components/upload/PackageUploader.vue'
import PackageExtractionProgress from '@/components/upload/PackageExtractionProgress.vue'
import ImageGrid from '@/components/files/ImageGrid.vue'
import ImageGridToolbar from '@/components/files/ImageGridToolbar.vue'
import ImagePreviewDialog from '@/components/files/ImagePreviewDialog.vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppEmptyState from '@/components/ui/AppEmptyState.vue'
import IconFolder from '@/components/icons/IconFolder.vue'
import IconImage from '@/components/icons/IconImage.vue'
import { usePackagesStore } from '@/stores/packages'
import type { ImageItem } from '@/components/files/ImageGrid.vue'
import type { PackageDetail } from '@/types/upload'

/**
 * 素材/产物页（/files），web.md §十一 / §十三 / §十四
 *
 * - query: ?folder=:pid  →  显示该 package 的产物网格
 * - query: ?tab=results   →  优先显示产物段（默认是素材段）
 * - 顶部工具栏：搜索 + 过滤
 * - 主体：ImageGrid（无 folder 时显示 AppEmptyState）
 *
 * 物料包解压进度（PackageExtractionProgress）以卡片形式贴在顶部。
 */
const route = useRoute()
const packages = usePackagesStore()

const activeFolderId = computed(() => {
  const v = route.query.folder
  return v != null ? Number(v) : null
})

const query = ref('')
const filter = ref<'all' | 'selected'>('all')
const selectedIds = ref<string[]>([])

const previewOpen = ref(false)
const previewItem = ref<ImageItem | null>(null)

const currentPkg = computed<PackageDetail | undefined>(() =>
  activeFolderId.value != null ? packages.get(activeFolderId.value) : undefined
)

// 简化：从 PackageDetail.images 拉数据
const items = computed<ImageItem[]>(() => {
  if (!currentPkg.value || !currentPkg.value.images) return []
  const q = query.value.trim().toLowerCase()
  return currentPkg.value.images
    .filter((im) => !q || im.filename.toLowerCase().includes(q))
    .map((im, idx) => ({
      id: String(im.imageId ?? idx),
      src: im.url ?? '',
      filename: im.filename,
      size: im.size,
      failed: !!im.failed,
    }))
})

onMounted(() => {
  // R6 阶段：根据 activeFolderId 调 API 加载 PackageDetail
  if (activeFolderId.value != null && !packages.get(activeFolderId.value)) {
    // 由 packages.refresh 之类的入口预拉，R6 阶段实接
  }
})

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

function onOpenExternal(item: ImageItem): void {
  if (item.src) window.open(item.src, '_blank')
}
</script>

<template>
  <section class="files-page flex flex-col h-full">
    <ImageGridToolbar
      v-model:query="query"
      v-model:filter="filter"
      :total="items.length"
      :selected-count="selectedIds.length"
      search-placeholder="搜索产物文件名..."
    >
      <template #actions>
        <slot name="actions" />
      </template>
    </ImageGridToolbar>

    <div class="files-page-body flex-1 overflow-y-auto">
      <!-- 上传区（始终展示，可被 query.upload=1 强调） -->
      <div class="px-4 pt-4">
        <PackageUploader v-if="!activeFolderId" />
      </div>

      <!-- 解压进度卡（仅选中 folder 时） -->
      <div v-if="activeFolderId && currentPkg" class="px-4 pt-4">
        <PackageExtractionProgress :pkg="currentPkg" />
      </div>

      <!-- 产物网格 -->
      <div v-if="activeFolderId" class="mt-2">
        <ImageGrid
          v-if="items.length > 0"
          :items="items"
          :selected-ids="selectedIds"
          checkable
          @update:selected-ids="(ids: string[]) => (selectedIds = ids)"
          @preview="onPreview"
          @download="onDownload"
          @open-external="onOpenExternal"
        />
        <AppEmptyState
          v-else
          :icon="IconImage"
          title="该素材包暂无产物"
          description="处理完成后会出现在这里"
        />
      </div>

      <!-- 顶层 /files：folder 概览 -->
      <div v-else class="px-4 py-6">
        <AppEmptyState
          :icon="IconFolder"
          title="选择素材包以查看产物"
          description="从左栏文件树选一个素材包，或在右栏查看任务进度"
        />
      </div>

      <AppCard v-if="previewOpen && previewItem" class="preview-tile" />
    </div>

    <ImagePreviewDialog
      v-if="previewItem"
      :open="previewOpen"
      :src="previewItem.src"
      :alt="previewItem.alt"
      :filename="previewItem.filename"
      :size="previewItem.size"
      @update:open="(v: boolean) => (previewOpen = v)"
      @download="onDownload(previewItem)"
      @open-external="onOpenExternal(previewItem)"
    />
  </section>
</template>