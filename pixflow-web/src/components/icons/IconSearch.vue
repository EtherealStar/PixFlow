<script setup lang="ts">
import { Search } from 'lucide-vue-next'
/**
 * IconSearch — 搜索（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Search
    :size="size ?? 24"
    :stroke-width="1.75"
    :class="$props.class"
  />
</template>