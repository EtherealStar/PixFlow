<script setup lang="ts">
import { ChevronLeft } from 'lucide-vue-next'
/**
 * IconChevronLeft — 面板收起/展开（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <ChevronLeft
    :size="size ?? 24"
    :stroke-width="1.75"
    :class="$props.class"
  />
</template>
