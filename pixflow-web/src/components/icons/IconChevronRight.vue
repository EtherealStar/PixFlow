<script setup lang="ts">
import { ChevronRight } from 'lucide-vue-next'
/**
 * IconChevronRight — 文件树/手风琴折叠（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <ChevronRight :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>