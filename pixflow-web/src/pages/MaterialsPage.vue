<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listOriginalImages, type OriginalImage } from '@/api/materials'
import ImageGrid from '@/components/files/ImageGrid.vue'
import ImageGridToolbar from '@/components/files/ImageGridToolbar.vue'
import AppEmptyState from '@/components/ui/AppEmptyState.vue'
import PackageUploader from '@/components/upload/PackageUploader.vue'
import IconImage from '@/components/icons/IconImage.vue'
import type { GalleryImageItem } from '@/types/files'

const router = useRouter()
const images = ref<OriginalImage[]>([])
const query = ref('')
const loading = ref(false)
const selectedIds = ref<string[]>([])
const filter = ref<'all' | 'failed' | 'selected'>('all')

const galleryItems = computed<GalleryImageItem[]>(() => images.value.map((image) => ({
  id: image.imageId,
  src: image.previewUrl ?? '',
  filename: image.displayName,
  size: formatSize(image),
  failed: !image.previewUrl
})).filter((image) => filter.value !== 'selected' || selectedIds.value.includes(image.id)))

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await listOriginalImages({ page: 1, size: 50, query: query.value, sort: 'CREATED_DESC' })
    images.value = page.records
  } finally {
    loading.value = false
  }
}

function openDetail(item: GalleryImageItem): void {
  const image = images.value.find((candidate) => candidate.imageId === item.id)
  if (image) void router.push(`/materials/packages/${image.packageId}/images/${image.imageId}`)
}

function openUrl(item: GalleryImageItem): void {
  if (item.src) window.open(item.src, '_blank', 'noopener,noreferrer')
}

function formatSize(image: OriginalImage): string {
  const dimensions = image.width && image.height ? `${image.width} x ${image.height}` : ''
  const bytes = image.sizeBytes === null || image.sizeBytes === undefined ? '' : `${Math.ceil(image.sizeBytes / 1024)} KB`
  return [dimensions, bytes].filter(Boolean).join(' · ')
}

onMounted(() => { void load() })
</script>

<template>
  <section class="flex h-full flex-col overflow-hidden bg-bg-page">
    <header class="flex items-center justify-between border-b border-border bg-bg-panel px-5 py-3">
      <h1 class="text-base font-semibold text-fg-primary">
        素材
      </h1>
      <PackageUploader />
    </header>
    <ImageGridToolbar
      v-model:query="query"
      v-model:filter="filter"
      :total="images.length"
      :selected-count="selectedIds.length"
      search-placeholder="搜索素材名称"
      @keyup.enter="load"
    />
    <div class="flex-1 overflow-y-auto">
      <div
        v-if="loading"
        class="p-8 text-center text-sm text-fg-muted"
      >
        正在加载素材...
      </div>
      <ImageGrid
        v-else-if="galleryItems.length > 0"
        :items="galleryItems"
        :selected-ids="selectedIds"
        checkable
        @update:selected-ids="(ids) => selectedIds = ids"
        @preview="openDetail"
        @download="openUrl"
        @open-external="openUrl"
      />
      <AppEmptyState
        v-else
        :icon="IconImage"
        title="暂无素材"
        description="上传压缩包后，原图会显示在这里"
      />
    </div>
  </section>
</template>
