<script setup lang="ts">
import { Minus } from 'lucide-vue-next'
/**
 * IconMinus — 移除（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <Minus :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>