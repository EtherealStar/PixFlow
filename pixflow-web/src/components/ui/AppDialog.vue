<script setup lang="ts">
import {
  DialogRoot,
  DialogPortal,
  DialogOverlay,
  DialogContent,
  DialogTitle,
  DialogDescription,
  DialogClose,
} from 'radix-vue'
import IconX from '@/components/icons/IconX.vue'

/**
 * AppDialog — 模态对话框（包装 radix-vue Dialog）
 *
 * 视觉（web.md §7.2）：
 * - overlay: bg-fg-primary/40
 * - content: bg-bg-panel rounded-xl shadow-lg p-6 max-w-lg w-[90vw]
 *
 * 用法（v-model 风格）：
 *   <AppDialog v-model:open="visible" title="..." description="...">
 *     <template #body>...</template>
 *     <template #footer>
 *       <AppButton @click="visible = false">取消</AppButton>
 *       <AppButton variant="primary">确认</AppButton>
 *     </template>
 *   </AppDialog>
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    description?: string
    /** 是否允许点击 overlay 关闭（默认 true） */
    closeOnOverlay?: boolean
  }>(),
  {
    closeOnOverlay: true,
  }
)

defineEmits<{
  'update:open': [v: boolean]
}>()

defineExpose({})
void props
</script>

<template>
  <DialogRoot :open="open" @update:open="(v: boolean) => $emit('update:open', v)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-50 bg-fg-primary/40 animate-in fade-in" />
      <DialogContent
        :class="[
          'fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2',
          'bg-bg-panel rounded-xl shadow-lg p-6',
          'w-[90vw] max-w-lg',
          'focus:outline-none'
        ]"
        @escape-key-down="$emit('update:open', false)"
        @pointer-down-outside="closeOnOverlay && $emit('update:open', false)"
      >
        <DialogTitle v-if="title" class="text-xl text-fg-primary mb-2 font-semibold">
          {{ title }}
        </DialogTitle>
        <DialogDescription v-if="description" class="text-sm text-fg-secondary mb-4">
          {{ description }}
        </DialogDescription>
        <DialogClose
          class="absolute top-4 right-4 p-1 rounded-sm text-fg-muted hover:bg-bg-sunken hover:text-fg-primary transition-colors"
          aria-label="关闭"
        >
          <IconX :size="16" />
        </DialogClose>

        <div class="dialog-body">
          <slot name="body" />
        </div>

        <div v-if="$slots.footer" class="dialog-footer mt-6 flex justify-end gap-2">
          <slot name="footer" />
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>