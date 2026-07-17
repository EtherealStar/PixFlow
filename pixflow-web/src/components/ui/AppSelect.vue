<script setup lang="ts">
import {
  SelectRoot,
  SelectTrigger,
  SelectValue,
  SelectPortal,
  SelectContent,
  SelectViewport,
  SelectItem,
  SelectItemText,
} from 'radix-vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'

/**
 * AppSelect — 下拉选择（包装 radix-vue Select）
 *
 * 触发器视觉：与 AppInput 同款（按钮形态）
 * 下拉视觉：bg-bg-panel shadow-lg rounded-md
 */
defineProps<{
  modelValue?: string
  options: Array<{ value: string; label: string }>
  placeholder?: string
}>()

defineEmits<{
  'update:modelValue': [v: string]
}>()
</script>

<template>
  <SelectRoot
    :model-value="modelValue"
    @update:model-value="(v: string) => $emit('update:modelValue', v)"
  >
    <SelectTrigger
      class="inline-flex items-center justify-between gap-2 h-10 px-3 text-base bg-bg-panel text-fg-primary border border-border rounded-md focus:outline-none focus:border-accent focus:ring-2 focus:ring-accent/20 transition-colors min-w-[120px]"
    >
      <SelectValue :placeholder="placeholder ?? '请选择'" />
      <IconChevronDown
        :size="16"
        class="text-fg-muted"
      />
    </SelectTrigger>
    <SelectPortal>
      <SelectContent
        position="popper"
        :side-offset="4"
        class="z-50 bg-bg-panel shadow-lg rounded-md border border-border overflow-hidden min-w-[var(--radix-select-trigger-width)]"
      >
        <SelectViewport class="p-1">
          <SelectItem
            v-for="opt in options"
            :key="opt.value"
            :value="opt.value"
            class="flex items-center gap-2 px-3 py-2 text-sm text-fg-primary rounded-sm cursor-pointer outline-none data-[highlighted]:bg-bg-sunken data-[state=checked]:text-accent"
          >
            <SelectItemText>{{ opt.label }}</SelectItemText>
          </SelectItem>
        </SelectViewport>
      </SelectContent>
    </SelectPortal>
  </SelectRoot>
</template>
