<script setup lang="ts">
/**
 * AppSegmented — 分段控制器（web.md §7.2）
 *
 * 用于视图切换（网格 / 列表）。
 *
 * 视觉：
 * - 容器 bg-bg-sunken p-1 rounded-md
 * - 选中项 bg-bg-panel shadow-sm
 * - 未选中 text-fg-secondary
 */
defineProps<{
  modelValue: string
  options: Array<{ value: string; label: string }>
}>()

defineEmits<{
  'update:modelValue': [v: string]
}>()
</script>

<template>
  <div class="inline-flex bg-bg-sunken p-1 rounded-md gap-1">
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      :class="[
        'px-3 h-8 rounded text-sm font-medium',
        'transition-colors',
        modelValue === opt.value
          ? 'bg-bg-panel text-fg-primary shadow-sm'
          : 'text-fg-secondary hover:text-fg-primary'
      ]"
      @click="$emit('update:modelValue', opt.value)"
    >
      {{ opt.label }}
    </button>
  </div>
</template>