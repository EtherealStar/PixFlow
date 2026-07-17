<script setup lang="ts">
import ImageCard from '@/components/files/ImageCard.vue'
import type { GalleryImageItem } from '@/types/files'

/**
 * ImageGrid — 自适应图片网格（web.md §7.3 / §十四）
 *
 * 列数：xl 5 / lg 4 / md 3 / sm 2 / 默认 2；最小列宽 200px。
 *
 * 单卡事件冒泡到上层，自身不持选中态。
 */
const props = defineProps<{
  items: GalleryImageItem[]
  selectedIds: string[]
  /** 是否允许选中（受工具栏 select-all 控制） */
  checkable?: boolean
}>()

const emit = defineEmits<{
  'update:selectedIds': [ids: string[]]
  preview: [item: GalleryImageItem]
  download: [item: GalleryImageItem]
  'open-external': [item: GalleryImageItem]
}>()

function toggle(id: string): void {
  const set = new Set(props.selectedIds)
  if (set.has(id)) set.delete(id)
  else set.add(id)
  emit('update:selectedIds', [...set])
}

function isSelected(id: string): boolean {
  return props.selectedIds.includes(id)
}
</script>

<template>
  <div
    class="image-grid grid gap-3 p-4"
    style="grid-template-columns: repeat(auto-fill, minmax(200px, 1fr))"
  >
    <ImageCard
      v-for="item in items"
      :key="item.id"
      :src="item.src"
      :alt="item.alt"
      :filename="item.filename"
      :size="item.size"
      :selected="isSelected(item.id)"
      :checkable="checkable"
      :failed="item.failed"
      @update:selected="() => toggle(item.id)"
      @preview="emit('preview', item)"
      @download="emit('download', item)"
      @open-external="emit('open-external', item)"
    />
  </div>
</template>
