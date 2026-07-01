<script setup lang="ts">
import { PinOff } from 'lucide-vue-next'
/**
 * IconPinOff — 取消钉住（lucide 转发）
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <PinOff :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>