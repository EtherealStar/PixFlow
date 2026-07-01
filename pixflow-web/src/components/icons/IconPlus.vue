<script setup lang="ts">
import { Plus } from 'lucide-vue-next'
/**
 * IconPlus — 添加（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Plus :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>