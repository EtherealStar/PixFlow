<script setup lang="ts">
import { Pencil } from 'lucide-vue-next'
/**
 * IconEdit — 重命名/编辑（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Pencil :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>