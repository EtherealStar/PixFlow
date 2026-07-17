<script setup lang="ts">
import { AccordionItem, AccordionHeader, AccordionTrigger, AccordionContent } from 'radix-vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'

/**
 * AppAccordionItem — 手风琴单个可折叠段
 *
 * 视觉（web.md §7.2）：
 * - item border-b border-border
 * - trigger py-3 px-2 flex items-center justify-between
 * - content pb-3 px-2 text-fg-secondary
 */
defineProps<{
  value: string
}>()
</script>

<template>
  <AccordionItem
    :value="value"
    class="border-b border-border"
  >
    <AccordionHeader>
      <AccordionTrigger
        class="w-full flex items-center justify-between py-3 px-2 text-sm font-medium text-fg-primary hover:bg-bg-sunken rounded-sm transition-colors"
      >
        <slot name="trigger" />
        <IconChevronDown
          :size="16"
          class="text-fg-muted transition-transform data-[state=open]:rotate-180"
        />
      </AccordionTrigger>
    </AccordionHeader>
    <AccordionContent class="pb-3 px-2 text-sm text-fg-secondary overflow-hidden data-[state=open]:animate-accordion-down data-[state=closed]:animate-accordion-up">
      <slot />
    </AccordionContent>
  </AccordionItem>
</template>