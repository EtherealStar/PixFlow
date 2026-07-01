<script setup lang="ts">
import { computed } from 'vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppSegmented from '@/components/ui/AppSegmented.vue'
import IconSearch from '@/components/icons/IconSearch.vue'

/**
 * ImageGridToolbar — 图片网格顶部工具栏（web.md §7.3）
 *
 * 左：搜索框（IconSearch 前缀）
 * 中：过滤 Segmented（all / failed / selected）
 * 右：批量操作槽（#actions）
 */
const props = defineProps<{
  query: string
  filter: 'all' | 'failed' | 'selected'
  total: number
  selectedCount: number
  searchPlaceholder?: string
}>()

const emit = defineEmits<{
  'update:query': [v: string]
  'update:filter': [v: 'all' | 'failed' | 'selected']
  selectAll: []
  clearSelection: []
}>()

const filterOptions = computed(() => [
  { label: `全部 (${props.total})`, value: 'all' },
  { label: `已选 (${props.selectedCount})`, value: 'selected' },
])
</script>

<template>
  <div class="image-grid-toolbar flex items-center gap-3 px-4 py-3 border-b border-border bg-bg-panel">
    <div class="relative flex-1 max-w-md">
      <span class="absolute inset-y-0 left-2 flex items-center text-fg-muted pointer-events-none">
        <IconSearch :size="14" />
      </span>
      <AppInput
        :model-value="query"
        class="pl-7"
        :placeholder="searchPlaceholder ?? '搜索文件名...'"
        @update:model-value="(v: string) => emit('update:query', v)"
      />
    </div>

    <AppSegmented
      :options="filterOptions"
      :model-value="filter"
      @update:model-value="(v: 'all' | 'selected') => emit('update:filter', v)"
    />

    <div class="flex items-center gap-2 ml-auto">
      <slot name="actions" />
    </div>
  </div>
</template>
