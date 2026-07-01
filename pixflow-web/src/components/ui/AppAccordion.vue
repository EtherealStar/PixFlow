<script setup lang="ts">
import { AccordionRoot, AccordionItem, AccordionHeader, AccordionTrigger, AccordionContent } from 'radix-vue'

/**
 * AppAccordion — 手风琴（包装 radix-vue Accordion）
 *
 * 视觉（web.md §7.2）：
 * - item border-b border-border
 * - trigger py-3 px-2 flex items-center justify-between
 * - content pb-3 px-2 text-fg-secondary
 */
defineProps<{
  /** 默认展开的 item values */
  defaultValue?: string[]
  /** 是否支持多 item 同时展开 */
  type?: 'single' | 'multiple'
}>()
</script>

<template>
  <AccordionRoot
    :type="type ?? 'multiple'"
    :default-value="defaultValue"
    class="border-t border-border"
  >
    <slot />
  </AccordionRoot>
</template>

<!-- 子组件在外部单独引入：AppAccordionItem / AppAccordionTrigger / AppAccordionContent -->