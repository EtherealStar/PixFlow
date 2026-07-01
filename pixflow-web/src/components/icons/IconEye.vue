<script setup lang="ts">
import { Eye } from 'lucide-vue-next'
/**
 * IconEye — 预览（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Eye :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>