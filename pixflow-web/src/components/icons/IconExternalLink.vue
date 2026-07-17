<script setup lang="ts">
import { ExternalLink } from 'lucide-vue-next'
/**
 * IconExternalLink — 在新窗口打开（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <ExternalLink
    :size="size ?? 24"
    :stroke-width="1.75"
    :class="$props.class"
  />
</template>