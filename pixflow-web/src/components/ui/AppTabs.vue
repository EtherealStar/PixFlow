<script setup lang="ts">
import { TabsRoot, TabsList } from 'radix-vue'

/**
 * AppTabs — 标签页（包装 radix-vue Tabs）
 *
 * 视觉（web.md §7.2）：
 * - trigger list border-b border-border
 * - active trigger border-b-2 border-accent text-fg-primary
 * - inactive text-fg-secondary
 *
 * 用法：trigger 直接用 radix-vue 的 TabsTrigger（已在 AppTabsTrigger 子组件中包装好）：
 *   <AppTabs v-model="activeTab" default-value="login">
 *     <template #list>
 *       <AppTabsTrigger value="login">登录</AppTabsTrigger>
 *       <AppTabsTrigger value="register">注册</AppTabsTrigger>
 *     </template>
 *     <AppTabsPanel value="login">...</AppTabsPanel>
 *     <AppTabsPanel value="register">...</AppTabsPanel>
 *   </AppTabs>
 */
defineProps<{
  defaultValue: string
  modelValue?: string
}>()

defineEmits<{
  'update:modelValue': [v: string]
}>()
</script>

<template>
  <TabsRoot
    :default-value="defaultValue"
    :model-value="modelValue"
    @update:model-value="(v: string) => $emit('update:modelValue', v)"
  >
    <TabsList class="flex border-b border-border mb-4">
      <slot name="list" />
    </TabsList>
    <slot />
  </TabsRoot>
</template>