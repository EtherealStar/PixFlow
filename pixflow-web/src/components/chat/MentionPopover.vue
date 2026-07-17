<script setup lang="ts">
import { computed, ref } from 'vue'
import AppPopover from '@/components/ui/AppPopover.vue'
import AppScrollArea from '@/components/ui/AppScrollArea.vue'
import { useFileIndexStore, type FileIndexNode } from '@/stores/fileIndex'
import IconPackage from '@/components/icons/IconPackage.vue'
import IconFolder from '@/components/icons/IconFolder.vue'
import IconChat from '@/components/icons/IconChat.vue'

/**
 * MentionPopover — @ 提及下拉（web.md §十二）
 *
 * - 数据源：useFileIndexStore.search(query)，本地模糊匹配，不发请求
 * - 键盘：↑↓ 选择 / Enter 确认 / Esc 取消
 * - 列表项：图标 + 文本 + 类型副标签
 */
const props = defineProps<{
  open: boolean
  query: string
}>()

const emit = defineEmits<{
  'update:open': [v: boolean]
  select: [node: FileIndexNode]
}>()

const fileIndex = useFileIndexStore()
const activeIndex = ref(0)

const results = computed(() => fileIndex.search(props.query))

function iconFor(type: FileIndexNode['type']) {
  switch (type) {
    case 'package':
      return IconPackage
    case 'conversation':
    case 'history':
      return IconChat
    default:
      return IconFolder
  }
}

function moveActive(delta: number): void {
  const len = results.value.length
  if (len === 0) return
  activeIndex.value = (activeIndex.value + delta + len) % len
}

function confirmActive(): void {
  const node = results.value[activeIndex.value]
  if (node) emit('select', node)
}

defineExpose({ moveActive, confirmActive })
</script>

<template>
  <AppPopover
    :default-open="open"
    @update:open="(v: boolean) => $emit('update:open', v)"
  >
    <template #trigger>
      <slot name="trigger" />
    </template>
    <AppScrollArea max-height="240px">
      <div
        v-if="results.length === 0"
        class="empty-hint"
      >
        无匹配项
      </div>
      <button
        v-for="(node, idx) in results"
        :key="node.id"
        type="button"
        :class="[
          'mention-item flex items-center gap-2 w-full text-left px-2 py-1.5 rounded-sm text-sm',
          idx === activeIndex ? 'bg-accent-soft text-fg-primary' : 'text-fg-primary hover:bg-bg-sunken'
        ]"
        @click="emit('select', node)"
        @mouseenter="activeIndex = idx"
      >
        <component
          :is="iconFor(node.type)"
          :size="16"
          class="text-fg-secondary shrink-0"
        />
        <span class="flex-1 truncate">{{ node.label }}</span>
        <span
          v-if="node.subtitle"
          class="text-xs text-fg-muted shrink-0"
        >{{ node.subtitle }}</span>
      </button>
    </AppScrollArea>
  </AppPopover>
</template>

<style scoped>
.empty-hint {
  padding: 12px;
  text-align: center;
  font-size: 13px;
  color: var(--fg-muted);
}
</style>
