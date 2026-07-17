<script setup lang="ts">
import { Pin } from 'lucide-vue-next'
/**
 * IconPin — 钉住侧栏（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Pin
    :size="size ?? 24"
    :stroke-width="1.75"
    :class="$props.class"
  />
</template>