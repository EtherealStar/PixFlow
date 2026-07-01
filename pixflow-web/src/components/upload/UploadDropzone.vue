<script setup lang="ts">
import { ref } from 'vue'
import IconUpload from '@/components/icons/IconUpload.vue'

/**
 * UploadDropzone — 拖拽上传区域（web.md §十三 / §7.3）
 *
 * - 整块可点击 / 拖入 file
 * - 接收后透传 payload 给上层（不在此处真正发起上传，由 PackageUploader 调度）
 */
const emit = defineEmits<{
  select: [files: File[]]
}>()

const isHover = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

function onPick(): void {
  inputRef.value?.click()
}

function onChange(ev: Event): void {
  const target = ev.target as HTMLInputElement
  if (!target.files || target.files.length === 0) return
  const files = Array.from(target.files)
  emit('select', files)
  target.value = ''
}

function onDrop(ev: DragEvent): void {
  ev.preventDefault()
  isHover.value = false
  const files = ev.dataTransfer?.files ? Array.from(ev.dataTransfer.files) : []
  if (files.length > 0) emit('select', files)
}

function onDragOver(ev: DragEvent): void {
  ev.preventDefault()
  isHover.value = true
}

function onDragLeave(): void {
  isHover.value = false
}
</script>

<template>
  <div
    :class="[
      'upload-dropzone flex flex-col items-center justify-center gap-2',
      'rounded-lg border-2 border-dashed cursor-pointer',
      'px-6 py-10 text-center transition-colors',
      isHover
        ? 'border-accent bg-accent-soft'
        : 'border-border bg-bg-sunken hover:border-fg-muted hover:bg-bg-panel'
    ]"
    role="button"
    tabindex="0"
    @click="onPick"
    @keydown.enter="onPick"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <IconUpload :size="32" :class="isHover ? 'text-accent' : 'text-fg-muted'" />
    <div class="text-sm text-fg-primary">拖入文件或点击选择</div>
    <div class="text-xs text-fg-muted">支持多文件 / 单个最大 200MB</div>
    <input ref="inputRef" type="file" multiple class="hidden" @change="onChange" />
  </div>
</template>