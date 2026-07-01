<script setup lang="ts">
import { TabsContent } from 'radix-vue'

/**
 * AppTabsPanel — Tabs 单个内容面板
 */
defineProps<{
  value: string
}>()
</script>

<template>
  <TabsContent
    :value="value"
    class="focus-visible:outline-none"
  >
    <slot />
  </TabsContent>
</template>