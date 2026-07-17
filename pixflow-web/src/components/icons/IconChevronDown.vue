<script setup lang="ts">
import { ChevronDown } from 'lucide-vue-next'
/**
 * IconChevronDown — 文件树/下拉展开（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <ChevronDown
    :size="size ?? 24"
    :stroke-width="1.75"
    :class="$props.class"
  />
</template>