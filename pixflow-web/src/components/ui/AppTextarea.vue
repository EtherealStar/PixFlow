<script setup lang="ts">
import IconAlertCircle from '@/components/icons/IconAlertCircle.vue'

/**
 * AppTextarea — 多行输入（web.md §7.2）
 *
 * 视觉与 AppInput 一致（边框 / focus / 圆角 / padding 同款 token）。
 * Composer 用。
 */
withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    rows?: number
    error?: string
    disabled?: boolean
  }>(),
  {
    modelValue: undefined,
    placeholder: undefined,
    rows: 3,
    error: undefined,
    disabled: false,
  }
)

defineEmits<{
  'update:modelValue': [v: string]
}>()
</script>

<template>
  <div class="textarea-wrap">
    <textarea
      :value="modelValue"
      :placeholder="placeholder"
      :rows="rows"
      :disabled="disabled"
      :class="[
        'w-full px-3 py-2',
        'rounded-md border',
        'bg-bg-panel text-base text-fg-primary placeholder:text-fg-muted',
        'transition-colors',
        'focus:outline-none focus:ring-2',
        error
          ? 'border-danger focus:border-danger focus:ring-danger/20'
          : 'border-border focus:border-accent focus:ring-accent/20',
        disabled && 'opacity-50 cursor-not-allowed bg-bg-sunken',
        'resize-y'
      ]"
      @input="(ev: Event) => $emit('update:modelValue', (ev.target as HTMLTextAreaElement).value)"
    />
    <p
      v-if="error"
      class="error-text flex items-center gap-1 text-xs text-danger mt-1"
    >
      <IconAlertCircle :size="12" />
      {{ error }}
    </p>
  </div>
</template>
