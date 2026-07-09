<script setup lang="ts">
import { computed, useSlots } from 'vue'
import IconAlertCircle from '@/components/icons/IconAlertCircle.vue'

/**
 * AppInput — 自绘输入框（web.md §7.2）
 *
 * 视觉：
 * - 边框 border，focus 边框 accent + ring accent/20
 * - 圆角 md
 * - text-base（14px）
 * - 支持 prefix / suffix 插槽（放图标）
 * - error 态：红边框 + IconAlertCircle
 *
 * 设计原则：颜色全部走 token。
 */
const props = withDefaults(
  defineProps<{
    modelValue?: string | number
    placeholder?: string
    /** 错误态：红边框 + 错误文案 */
    error?: string
    /** 是否禁用 */
    disabled?: boolean
    /** input type */
    type?: 'text' | 'password' | 'email' | 'number' | 'search'
    /** 浏览器自动填充提示，透传到原生 input。 */
    autocomplete?: string
    /** 原生 input name，便于密码管理器识别表单字段。 */
    name?: string
  }>(),
  {
    type: 'text',
    disabled: false,
  }
)

defineEmits<{
  'update:modelValue': [v: string | number]
}>()

const slots = useSlots()
const hasPrefix = computed(() => !!slots.prefix)
const hasSuffix = computed(() => !!slots.suffix)
</script>

<template>
  <div class="input-wrap">
    <div
      :class="[
        'input-shell',
        'flex items-center gap-2',
        'rounded-md border',
        'bg-bg-panel',
        'transition-colors',
        error
          ? 'border-danger focus-within:border-danger focus-within:ring-2 focus-within:ring-danger/20'
          : 'border-border focus-within:border-accent focus-within:ring-2 focus-within:ring-accent/20',
        disabled && 'opacity-50 cursor-not-allowed bg-bg-sunken'
      ]"
    >
      <span v-if="hasPrefix" class="prefix-slot pl-2 text-fg-muted">
        <slot name="prefix" />
      </span>
      <input
        :value="modelValue"
        :placeholder="placeholder"
        :type="type"
        :autocomplete="autocomplete"
        :name="name"
        :disabled="disabled"
        class="flex-1 min-w-0 h-10 px-3 text-base bg-transparent text-fg-primary placeholder:text-fg-muted focus:outline-none disabled:cursor-not-allowed"
        @input="(ev: Event) => $emit('update:modelValue', (ev.target as HTMLInputElement).value)"
      />
      <span v-if="hasSuffix" class="suffix-slot pr-2 text-fg-muted">
        <slot name="suffix" />
      </span>
      <IconAlertCircle v-if="error" :size="16" class="text-danger mr-2 shrink-0" />
    </div>
    <p v-if="error" class="error-text text-xs text-danger mt-1">{{ error }}</p>
  </div>
</template>
