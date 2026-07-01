<script setup lang="ts">
import AppButton from './AppButton.vue'

/**
 * AppEmptyState — 空状态（web.md §7.2）
 *
 * 视觉：
 * - 居中
 * - 图标 48px text-fg-muted
 * - 标题 text-lg
 * - 副标题 text-sm text-fg-secondary
 * - 可选主操作 AppButton
 */
withDefaults(
  defineProps<{
    /** 图标（建议 48px 内置 Icon* 组件） */
    icon?: unknown
    title?: string
    description?: string
    /** 主操作按钮文案 */
    actionLabel?: string
  }>(),
  {}
)

defineEmits<{ action: [] }>()
</script>

<template>
  <div class="flex flex-col items-center justify-center text-center py-12 px-4">
    <component
      :is="icon"
      v-if="icon"
      :size="48"
      class="text-fg-muted mb-4"
    />
    <h3 v-if="title" class="text-lg text-fg-primary mb-1">{{ title }}</h3>
    <p v-if="description" class="text-sm text-fg-secondary mb-4 max-w-sm">{{ description }}</p>
    <AppButton v-if="actionLabel" variant="primary" @click="$emit('action')">
      {{ actionLabel }}
    </AppButton>
    <slot v-else />
  </div>
</template>