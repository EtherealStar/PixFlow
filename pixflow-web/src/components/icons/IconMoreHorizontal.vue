<script setup lang="ts">
import { MoreHorizontal } from 'lucide-vue-next'
/**
 * IconMoreHorizontal — 更多操作（DropdownMenu 触发，lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <MoreHorizontal :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>