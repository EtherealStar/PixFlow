<script setup lang="ts">
import { Download } from 'lucide-vue-next'
/**
 * IconDownload — 下载（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Download
    :size="size ?? 24"
    :stroke-width="1.75"
    :class="$props.class"
  />
</template>