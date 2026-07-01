<script setup lang="ts">
import { Trash2 } from 'lucide-vue-next'
/**
 * IconTrash — 删除（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Trash2 :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>