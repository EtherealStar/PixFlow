<script setup lang="ts">
import { computed } from 'vue'
import AppCheckbox from '@/components/ui/AppCheckbox.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconDownload from '@/components/icons/IconDownload.vue'
import IconExternalLink from '@/components/icons/IconExternalLink.vue'

/**
 * ImageCard — 4:3 缩略图卡片（web.md §7.3 / §十四）
 *
 * 视觉：
 * - 4:3 容器，rounded-md，bg-bg-sunken 占位 + IconImage 占位符
 * - 顶部悬停动作层：选中 checkbox + 下载 + 在新窗口打开
 * - 底部 caption：文件名 + 尺寸 / 大小
 * - 选中态：ring-2 ring-accent
 */
const props = defineProps<{
  /** 数据对象，至少含 id + src/url + filename */
  src: string
  alt?: string
  filename: string
  size?: string
  selected?: boolean
  /** 是否允许选中 */
  checkable?: boolean
  /** 加载/无效占位 */
  failed?: boolean
}>()

const emit = defineEmits<{
  'update:selected': [v: boolean]
  preview: []
  download: []
  'open-external': []
}>()

const aspectBox = computed(() => 'aspect-[4/3]')
</script>

<template>
  <div
    :class="[
      'image-card group relative rounded-md overflow-hidden bg-bg-sunken',
      'border border-border hover:border-fg-muted transition-colors',
      selected ? 'ring-2 ring-accent border-accent' : ''
    ]"
  >
    <!-- 主体 4:3 区域 -->
    <button
      type="button"
      :aria-label="`预览 ${filename}`"
      class="block w-full"
      @click="emit('preview')"
    >
      <div :class="['w-full', aspectBox, 'flex items-center justify-center']">
        <img
          v-if="!failed && src"
          :src="src"
          :alt="alt ?? filename"
          class="w-full h-full object-cover"
          loading="lazy"
        />
        <IconImage v-else :size="32" class="text-fg-muted" />
      </div>
    </button>

    <!-- hover 动作层 -->
    <div
      class="image-card-actions absolute top-1.5 left-1.5 right-1.5 flex items-start justify-between gap-1 opacity-0 group-hover:opacity-100 transition-opacity"
    >
      <AppCheckbox
        v-if="checkable"
        :checked="selected"
        @update:checked="(v: boolean) => emit('update:selected', v)"
      />
      <div v-else class="h-4 w-4" />
      <div class="flex gap-1">
        <button
          type="button"
          class="h-6 w-6 flex items-center justify-center rounded-sm bg-bg-panel/90 text-fg-primary hover:bg-bg-panel"
          aria-label="下载"
          @click.stop="emit('download')"
        >
          <IconDownload :size="14" />
        </button>
        <button
          type="button"
          class="h-6 w-6 flex items-center justify-center rounded-sm bg-bg-panel/90 text-fg-primary hover:bg-bg-panel"
          aria-label="在新窗口打开"
          @click.stop="emit('open-external')"
        >
          <IconExternalLink :size="14" />
        </button>
      </div>
    </div>

    <!-- caption -->
    <div class="px-2 py-1.5 bg-bg-panel border-t border-border">
      <div class="text-xs text-fg-primary truncate" :title="filename">{{ filename }}</div>
      <div v-if="size" class="text-[10px] text-fg-muted truncate">{{ size }}</div>
    </div>
  </div>
</template>
