<script setup lang="ts">
import { AlertCircle } from 'lucide-vue-next'
/**
 * IconAlertCircle — 错误/告警提示（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <AlertCircle
    :size="size ?? 24"
    :stroke-width="1.75"
    :class="$props.class"
  />
</template>