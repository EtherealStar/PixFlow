<script setup lang="ts">
import { CheckboxRoot, CheckboxIndicator } from 'radix-vue'
import IconCheck from '@/components/icons/IconCheck.vue'

/**
 * AppCheckbox — 自绘复选框（包装 radix-vue Checkbox）
 *
 * 视觉（web.md §7.2 / §5.4）：
 * - 默认 size 16×16，rounded 4px
 * - 未选中：bg-bg-panel + border-border
 * - 选中：bg-accent + IconCheck（stroke=2，color accent-fg）
 * - disabled：opacity-50 + cursor-not-allowed
 *
 * 用法（v-model 风格）：
 *   <AppCheckbox v-model:checked="selected" />
 *   <AppCheckbox :checked="allSelected" @update:checked="..." />
 */
const props = defineProps<{
  checked: boolean
  disabled?: boolean
  /** 原生 aria-label */
  label?: string
}>()

defineEmits<{
  'update:checked': [v: boolean]
}>()

void props
</script>

<template>
  <CheckboxRoot
    :checked="checked"
    :disabled="disabled"
    :aria-label="label"
    class="app-checkbox h-4 w-4 rounded-[4px] border border-border bg-bg-panel data-[state=checked]:bg-accent data-[state=checked]:border-accent disabled:opacity-50 disabled:cursor-not-allowed focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 transition-colors"
    @update:checked="(v: boolean) => $emit('update:checked', v)"
  >
    <CheckboxIndicator class="flex items-center justify-center text-accent-fg">
      <IconCheck
        :size="12"
        :stroke-width="2.5"
      />
    </CheckboxIndicator>
  </CheckboxRoot>
</template>
