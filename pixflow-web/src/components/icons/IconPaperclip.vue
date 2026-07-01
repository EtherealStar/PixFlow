<script setup lang="ts">
import { Paperclip } from 'lucide-vue-next'
/**
 * IconPaperclip — 附件（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Paperclip :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>