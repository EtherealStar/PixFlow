<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppTabs from '@/components/ui/AppTabs.vue'
import AppTabsTrigger from '@/components/ui/AppTabsTrigger.vue'
import AppTabsPanel from '@/components/ui/AppTabsPanel.vue'
import AppSegmented from '@/components/ui/AppSegmented.vue'
import AppSelect from '@/components/ui/AppSelect.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppAccordion from '@/components/ui/AppAccordion.vue'
import AppAccordionItem from '@/components/ui/AppAccordionItem.vue'
import AppEmptyState from '@/components/ui/AppEmptyState.vue'

import PackageUploader from '@/components/upload/PackageUploader.vue'
import ImageGrid from '@/components/files/ImageGrid.vue'
import ImageGridToolbar from '@/components/files/ImageGridToolbar.vue'
import ImagePreviewDialog from '@/components/files/ImagePreviewDialog.vue'

import IconFolder from '@/components/icons/IconFolder.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconTrash from '@/components/icons/IconTrash.vue'

import { listPackages, listPackageImages, deletePackageImage } from '@/api/packages'
import { listConversations } from '@/api/conversations'
import { listConversationImages, deleteTaskResult } from '@/api/tasks'
import { createBundleDownload } from '@/api/downloads'

import type { GalleryImageItem } from '@/types/files'
import type { ViewMode, SortType } from '@/types/upload'

// -- Router & Tabs --
const route = useRoute()

const activeTab = ref(route.query.tab === 'results' || route.query.focus === 'results' ? 'products' : 'materials')

watch(() => route.query.tab, (newTab) => {
  if (newTab === 'results') activeTab.value = 'products'
  else if (newTab === 'materials') activeTab.value = 'materials'
})
watch(() => route.query.focus, (newFocus) => {
  if (newFocus === 'results') activeTab.value = 'products'
})

// -- View Controls --
const viewMode = ref<ViewMode>('folder')
const sortType = ref<SortType>('time')
const query = ref('')
const filter = ref<'all' | 'selected'>('all')

const selectedIds = ref<string[]>([])

// -- Dialogs --
const previewOpen = ref(false)
const previewItem = ref<GalleryImageItem | null>(null)

// -- Data --
interface ExtendedImageItem extends GalleryImageItem {
  createdAt?: string
  groupId?: string
  sourceType: 'asset' | 'result'
  packageId?: number
  imageId?: string
  taskId?: string
  resultId?: string
}

interface GroupedData {
  id: string
  title: string
  count: number
  images: ExtendedImageItem[]
}

const materialsData = ref<GroupedData[]>([])
const productsData = ref<GroupedData[]>([])
const loadingMaterials = ref(false)
const loadingProducts = ref(false)

async function loadMaterials() {
  loadingMaterials.value = true
  try {
    const res = await listPackages({ size: 100 })
    const groups = await Promise.all(res.items.map(async pkg => {
      const packageId = pkg.packageId ?? pkg.id
      if (!packageId) {
        return { id: 'unknown', title: pkg.name, count: 0, images: [] as ExtendedImageItem[] }
      }
      const imagePage = await listPackageImages(packageId, { size: 200 })
      const images: ExtendedImageItem[] = imagePage.items.map(im => ({
        id: `asset-${im.imageId}`,
        src: im.url ?? '',
        filename: im.displayName || im.filename || im.originalPath,
        size: formatBytes(im.size),
        failed: !im.url,
        createdAt: im.createdAt || new Date().toISOString(),
        groupId: String(packageId),
        sourceType: 'asset',
        packageId,
        imageId: im.imageId
      }))
      return {
        id: String(packageId),
        title: pkg.name,
        count: images.length,
        images
      }
    }))
    materialsData.value = groups
  } catch (e) {
    console.error(e)
  }
  loadingMaterials.value = false
}

async function loadProducts() {
  loadingProducts.value = true
  try {
    const res = await listConversations({ size: 100 })
    const groups = await Promise.all(res.items.map(async conv => {
      try {
        const resultsRes = await listConversationImages(conv.conversationId, { size: 200 })
        const convImages: ExtendedImageItem[] = resultsRes.items.map(result => ({
          id: `result-${result.resultId}`,
          src: result.url ?? '',
          filename: result.displayName || result.filename || `Result-${result.resultId}.png`,
          size: formatBytes(result.size),
          failed: !result.url,
          createdAt: result.createdAt,
          groupId: conv.conversationId,
          sourceType: 'result',
          taskId: result.taskId,
          resultId: result.resultId
        }))
        return {
          id: conv.conversationId,
          title: conv.title || '无标题会话',
          count: convImages.length,
          images: convImages
        }
      } catch (err) {
        console.error('Failed to load images for conversation', conv.conversationId, err)
        return {
          id: conv.conversationId,
          title: conv.title || '无标题会话',
          count: 0,
          images: [] as ExtendedImageItem[]
        }
      }
    }))
    productsData.value = groups
  } catch(e) {
    console.error(e)
  }
  loadingProducts.value = false
}

onMounted(() => {
  void Promise.all([loadMaterials(), loadProducts()]).catch((error: unknown) => {
    console.error('Failed to load files page', error)
  })
})

// -- Computed Data for Display --
const currentGroups = computed(() => {
  const groups = activeTab.value === 'materials' ? materialsData.value : productsData.value
  
  // Clone to avoid mutating original
  return groups.map(g => {
    // 1. Filter by search query
    let filteredImages = g.images
    const q = query.value.trim().toLowerCase()
    if (q) {
      filteredImages = filteredImages.filter(img => img.filename.toLowerCase().includes(q))
    }
    
    // 2. Filter by selection
    if (filter.value === 'selected') {
      filteredImages = filteredImages.filter(img => selectedIds.value.includes(img.id))
    }
    
    // 3. Sort
    filteredImages = [...filteredImages].sort((a, b) => {
      if (sortType.value === 'name') {
        return a.filename.localeCompare(b.filename)
      } else {
        const timeA = a.createdAt ? new Date(a.createdAt).getTime() : 0
        const timeB = b.createdAt ? new Date(b.createdAt).getTime() : 0
        return timeB - timeA // desc
      }
    })
    
    return { ...g, images: filteredImages }
  }).filter(g => g.images.length > 0) // only show groups with matching images
})

const currentFlatImages = computed(() => {
  let allImages: ExtendedImageItem[] = []
  const groups = activeTab.value === 'materials' ? materialsData.value : productsData.value
  
  groups.forEach(g => {
    allImages.push(...g.images)
  })
  
  // Filter by query
  const q = query.value.trim().toLowerCase()
  if (q) {
    allImages = allImages.filter(img => img.filename.toLowerCase().includes(q))
  }
  
  // Filter by selection
  if (filter.value === 'selected') {
    allImages = allImages.filter(img => selectedIds.value.includes(img.id))
  }
  
  // Sort
  allImages = [...allImages].sort((a, b) => {
    if (sortType.value === 'name') {
      return a.filename.localeCompare(b.filename)
    } else {
      const timeA = a.createdAt ? new Date(a.createdAt).getTime() : 0
      const timeB = b.createdAt ? new Date(b.createdAt).getTime() : 0
      return timeB - timeA // desc
    }
  })
  
  return allImages
})

// Calculate total items (before selected filter)
const totalItems = computed(() => {
  const groups = activeTab.value === 'materials' ? materialsData.value : productsData.value
  let count = 0
  const q = query.value.trim().toLowerCase()
  groups.forEach(g => {
    g.images.forEach(img => {
      if (!q || img.filename.toLowerCase().includes(q)) {
        count++
      }
    })
  })
  return count
})

// -- Actions --
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

async function onDownloadSelected() {
  const items = selectedItems()
  if (!items.length) return
  const bundleItems = items.map(item => item.sourceType === 'asset'
    ? { type: 'ASSET_IMAGE' as const, imageId: item.imageId!, filename: item.filename }
    : { type: 'TASK_RESULT' as const, resultId: item.resultId!, filename: item.filename })
  const handle = await createBundleDownload(bundleItems, activeTab.value === 'materials' ? 'materials.zip' : 'products.zip')
  const a = document.createElement('a')
  a.href = handle.url
  a.download = activeTab.value === 'materials' ? 'materials.zip' : 'products.zip'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

function onOpenExternal(item: GalleryImageItem): void {
  if (item.src) window.open(item.src, '_blank')
}

async function handleDeleteSelected() {
  if (!selectedIds.value.length) return
  const confirmMsg = `确定要删除选中的 ${selectedIds.value.length} 项吗？`
  if (window.confirm(confirmMsg)) {
    const items = selectedItems()
    await Promise.all(items.map(item => {
      if (item.sourceType === 'asset' && item.packageId !== undefined && item.imageId) {
        return deletePackageImage(item.packageId, item.imageId)
      }
      if (item.sourceType === 'result' && item.taskId && item.resultId) {
        return deleteTaskResult(item.taskId, item.resultId)
      }
      return Promise.resolve()
    }))
    selectedIds.value = []
    if (activeTab.value === 'materials') await loadMaterials()
    else await loadProducts()
  }
}

function selectedItems(): ExtendedImageItem[] {
  const selected = new Set(selectedIds.value)
  const groups = activeTab.value === 'materials' ? materialsData.value : productsData.value
  return groups.flatMap(g => g.images).filter(img => selected.has(img.id))
}

function formatBytes(size?: number | null): string {
  if (size === null || size === undefined || size < 0) return '未知'
  if (size < 1024) return `${size} B`
  const kb = size / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  return `${(kb / 1024).toFixed(1)} MB`
}
</script>

<template>
  <section class="files-page flex flex-col h-full bg-bg-page overflow-hidden">
    <AppTabs
      v-model="activeTab"
      :default-value="'materials'"
      class="flex flex-col h-full overflow-hidden"
    >
      <template #list>
        <div class="px-4 pt-2">
          <div class="flex">
            <AppTabsTrigger value="materials">
              素材
            </AppTabsTrigger>
            <AppTabsTrigger value="products">
              产物
            </AppTabsTrigger>
          </div>
        </div>
      </template>
      
      <!-- 工具栏 -->
      <ImageGridToolbar
        v-model:query="query"
        v-model:filter="filter"
        :total="totalItems"
        :selected-count="selectedIds.length"
        :search-placeholder="activeTab === 'materials' ? '搜索素材文件名...' : '搜索产物文件名...'"
      >
        <template #actions>
          <AppSegmented
            v-model="viewMode"
            :options="[{ label: '按文件夹', value: 'folder' }, { label: '平铺', value: 'flat' }]"
          />
          <AppSelect
            v-model="sortType"
            :options="[{ label: '按时间', value: 'time' }, { label: '按名称', value: 'name' }]"
          />
          <AppButton 
            v-if="selectedIds.length > 0" 
            variant="secondary"
            class="h-10 ml-2"
            @click="onDownloadSelected"
          >
            打包下载 ({{ selectedIds.length }})
          </AppButton>
          <AppButton 
            v-if="selectedIds.length > 0" 
            variant="danger" 
            class="h-10 ml-2" 
            @click="handleDeleteSelected"
          >
            <IconTrash
              :size="16"
              class="mr-1"
            />
            删除 ({{ selectedIds.length }})
          </AppButton>
        </template>
      </ImageGridToolbar>

      <!-- 主要内容区 -->
      <div class="flex-1 overflow-y-auto relative">
        <AppTabsPanel
          value="materials"
          class="h-full absolute inset-0 overflow-y-auto"
        >
          <!-- 上传区 -->
          <div class="px-4 pt-4">
            <PackageUploader />
          </div>

          <div
            v-if="loadingMaterials"
            class="p-8 flex justify-center text-fg-muted"
          >
            正在加载素材...
          </div>
          <div
            v-else-if="viewMode === 'folder'"
            class="p-4"
          >
            <AppAccordion
              type="multiple"
              :default-value="materialsData.map(g => g.id)"
            >
              <AppAccordionItem
                v-for="group in currentGroups"
                :key="group.id"
                :value="group.id"
              >
                <template #trigger>
                  <div class="flex items-center gap-2">
                    <IconFolder
                      :size="16"
                      class="text-accent"
                    />
                    <span>{{ group.title }} ({{ group.images.length }})</span>
                  </div>
                </template>
                <ImageGrid
                  :items="group.images"
                  :selected-ids="selectedIds"
                  checkable
                  @update:selected-ids="(ids: string[]) => (selectedIds = ids)"
                  @preview="onPreview"
                  @download="onDownload"
                  @open-external="onOpenExternal"
                />
              </AppAccordionItem>
            </AppAccordion>
            <AppEmptyState
              v-if="currentGroups.length === 0"
              :icon="IconImage"
              title="暂无素材"
              description="没有找到匹配的素材图片"
            />
          </div>
          <div
            v-else
            class="p-4"
          >
            <ImageGrid
              v-if="currentFlatImages.length > 0"
              :items="currentFlatImages"
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
              title="暂无素材"
              description="没有找到匹配的素材图片"
            />
          </div>
        </AppTabsPanel>

        <AppTabsPanel
          value="products"
          class="h-full absolute inset-0 overflow-y-auto"
        >
          <div
            v-if="loadingProducts"
            class="p-8 flex justify-center text-fg-muted"
          >
            正在加载产物...
          </div>
          <div
            v-else-if="viewMode === 'folder'"
            class="p-4"
          >
            <AppAccordion
              type="multiple"
              :default-value="productsData.map(g => g.id)"
            >
              <AppAccordionItem
                v-for="group in currentGroups"
                :key="group.id"
                :value="group.id"
              >
                <template #trigger>
                  <div class="flex items-center gap-2">
                    <IconFolder
                      :size="16"
                      class="text-accent"
                    />
                    <span>{{ group.title }} ({{ group.images.length }})</span>
                  </div>
                </template>
                <ImageGrid
                  :items="group.images"
                  :selected-ids="selectedIds"
                  checkable
                  @update:selected-ids="(ids: string[]) => (selectedIds = ids)"
                  @preview="onPreview"
                  @download="onDownload"
                  @open-external="onOpenExternal"
                />
              </AppAccordionItem>
            </AppAccordion>
            <AppEmptyState
              v-if="currentGroups.length === 0"
              :icon="IconImage"
              title="暂无产物"
              description="没有找到匹配的产物图片"
            />
          </div>
          <div
            v-else
            class="p-4"
          >
            <ImageGrid
              v-if="currentFlatImages.length > 0"
              :items="currentFlatImages"
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
              title="暂无产物"
              description="没有找到匹配的产物图片"
            />
          </div>
        </AppTabsPanel>
      </div>
    </AppTabs>

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
