<script setup lang="ts">
import AppDialog from '@/components/ui/AppDialog.vue'
import AppButton from '@/components/ui/AppButton.vue'
import IconDownload from '@/components/icons/IconDownload.vue'
import IconExternalLink from '@/components/icons/IconExternalLink.vue'

/**
 * ImagePreviewDialog — 图片预览大图弹窗（web.md §十四）
 *
 * 包装 AppDialog（max-w-4xl）：
 * - 主图居中，object-contain，bg-bg-sunken
 * - 底部 caption + 下载 / 在新窗口打开
 */
defineProps<{
  open: boolean
  src: string
  alt?: string
  filename: string
  size?: string
}>()

const emit = defineEmits<{
  'update:open': [v: boolean]
  download: []
  'open-external': []
}>()
</script>

<template>
  <AppDialog
    :open="open"
    :title="filename"
    @update:open="(v: boolean) => $emit('update:open', v)"
  >
    <template #body>
      <div class="flex flex-col gap-3">
        <div class="bg-bg-sunken rounded-md flex items-center justify-center min-h-[320px] max-h-[60vh] overflow-hidden">
          <img
            :src="src"
            :alt="alt ?? filename"
            class="max-w-full max-h-[60vh] object-contain"
          >
        </div>
        <div class="flex items-center justify-between text-xs text-fg-secondary">
          <span v-if="size">{{ size }}</span>
          <span v-else />
          <div class="flex gap-2">
            <AppButton
              size="sm"
              variant="ghost"
              @click="emit('download')"
            >
              <IconDownload
                :size="14"
                class="mr-1"
              />
              下载
            </AppButton>
            <AppButton
              size="sm"
              variant="ghost"
              @click="emit('open-external')"
            >
              <IconExternalLink
                :size="14"
                class="mr-1"
              />
              新窗口打开
            </AppButton>
          </div>
        </div>
      </div>
    </template>
  </AppDialog>
</template>
