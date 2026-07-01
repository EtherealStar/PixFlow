<script setup lang="ts">
import { Copy } from 'lucide-vue-next'
/**
 * IconCopy — 复制（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Copy :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>