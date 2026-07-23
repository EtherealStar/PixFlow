<script setup lang="ts">
import {
  PopoverRoot,
  PopoverTrigger,
  PopoverPortal,
  PopoverContent,
  PopoverArrow,
} from 'radix-vue'

/**
 * AppPopover — 浮层弹出（包装 radix-vue Popover）
 *
 * 视觉（web.md §7.2）：bg-bg-panel shadow-lg rounded-md p-2
 * 用于 @提及下拉、文件树节点操作等场景。
 */
withDefaults(
  defineProps<{
    open?: boolean
    /** 默认 open 状态 */
    defaultOpen?: boolean
    side?: 'top' | 'bottom' | 'left' | 'right'
  }>(),
  {
    defaultOpen: false,
    side: 'bottom',
  }
)

defineEmits<{ 'update:open': [value: boolean] }>()
</script>

<template>
  <PopoverRoot
    :open="open"
    :default-open="defaultOpen"
    @update:open="(value: boolean) => $emit('update:open', value)"
  >
    <PopoverTrigger as-child>
      <slot name="trigger" />
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent
        :side="side"
        :side-offset="6"
        class="z-50 bg-bg-panel shadow-lg rounded-md p-2 border border-border"
      >
        <slot />
        <PopoverArrow
          class="fill-bg-panel"
          :width="10"
          :height="5"
        />
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>
