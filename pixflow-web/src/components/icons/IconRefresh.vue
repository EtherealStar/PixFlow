<script setup lang="ts">
import { RefreshCw } from 'lucide-vue-next'
/**
 * IconRefresh — 重试/刷新（lucide 转发）
 *
 * 注意：web.md §7.4 标注 "IconRefresh"，lucide 实际命名为 RefreshCw，
 * 这里通过 IconRefresh 别名暴露，保持调用一致性。
 */
defineProps<{ size?: number; class?: string }>()
</script>
<template>
  <RefreshCw :size="size ?? 24" :stroke-width="1.75" :class="$props.class" />
</template>