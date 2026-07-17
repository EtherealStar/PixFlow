<script setup lang="ts">
import { SwitchRoot, SwitchThumb } from 'radix-vue'

/**
 * AppSwitch — 开关（包装 radix-vue Switch）
 *
 * 视觉（web.md §7.2）：
 * - 轨道 36×20
 * - 拇指 16×16
 * - 轨道 bg-bg-sunken
 * - checked 走 bg-accent
 * - 圆角 full
 */
defineProps<{
  modelValue?: boolean
  disabled?: boolean
}>()

defineEmits<{
  'update:modelValue': [v: boolean]
}>()
</script>

<template>
  <SwitchRoot
    :model-value="modelValue"
    :disabled="disabled"
    :class="[
      'inline-flex items-center h-5 w-9 rounded-full p-0.5',
      'transition-colors',
      'bg-bg-sunken data-[state=checked]:bg-accent',
      'disabled:opacity-50 disabled:cursor-not-allowed',
      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/20'
    ]"
    @update:model-value="(v: boolean) => $emit('update:modelValue', v)"
  >
    <SwitchThumb
      class="block w-4 h-4 rounded-full bg-bg-panel shadow-sm transition-transform data-[state=checked]:translate-x-4 translate-x-0"
    />
  </SwitchRoot>
</template>