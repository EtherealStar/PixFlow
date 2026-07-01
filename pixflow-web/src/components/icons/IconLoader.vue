<script setup lang="ts">
import { Loader2 } from 'lucide-vue-next'
/**
 * IconLoader — loading 旋转图标（lucide 转发）
 * 用法：<IconLoader class="animate-spin" />
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Loader2 :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>