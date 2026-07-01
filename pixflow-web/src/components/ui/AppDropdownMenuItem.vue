<script setup lang="ts">
import { DropdownMenuItem } from 'radix-vue'

/**
 * AppDropdownMenuItem — DropdownMenu 子项
 *
 * 用法：
 *   <AppDropdownMenuItem @select="onRename">重命名</AppDropdownMenuItem>
 *   <AppDropdownMenuItem danger @select="onDelete">删除</AppDropdownMenuItem>
 */
withDefaults(
  defineProps<{
    /** 危险样式（红色文字） */
    danger?: boolean
    /** 禁用 */
    disabled?: boolean
  }>(),
  {
    danger: false,
    disabled: false,
  }
)

defineEmits<{
  select: [ev: Event]
}>()
</script>

<template>
  <DropdownMenuItem
    :disabled="disabled"
    :class="[
      'px-3 py-2 text-sm rounded-sm cursor-pointer outline-none',
      'transition-colors',
      danger
        ? 'text-danger data-[highlighted]:bg-danger-soft'
        : 'text-fg-primary data-[highlighted]:bg-bg-sunken'
    ]"
    @select="(ev: Event) => $emit('select', ev)"
  >
    <slot />
  </DropdownMenuItem>
</template>