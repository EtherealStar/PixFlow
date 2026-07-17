<script setup lang="ts">
import { Send } from 'lucide-vue-next'
/**
 * IconSend — 发送消息（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Send
    :size="size ?? 24"
    :stroke-width="1.75"
    :class="$props.class"
  />
</template>