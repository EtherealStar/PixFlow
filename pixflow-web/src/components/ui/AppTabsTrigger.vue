<script setup lang="ts">
import { TabsTrigger } from 'radix-vue'

/**
 * AppTabsTrigger — Tabs 单个标签触发器
 */
defineProps<{
  value: string
}>()
</script>

<template>
  <TabsTrigger
    :value="value"
    :class="[
      'px-4 py-2 text-sm font-medium',
      'border-b-2 -mb-px',
      'transition-colors',
      'text-fg-secondary hover:text-fg-primary',
      'data-[state=active]:border-accent data-[state=active]:text-fg-primary',
      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/20'
    ]"
  >
    <slot />
  </TabsTrigger>
</template>