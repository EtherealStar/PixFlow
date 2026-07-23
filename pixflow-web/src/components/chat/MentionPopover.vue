<script setup lang="ts">
import { ref, watch } from 'vue'
import AppPopover from '@/components/ui/AppPopover.vue'
import AppScrollArea from '@/components/ui/AppScrollArea.vue'
import { searchAssetReferences, type AssetReferenceCandidate } from '@/api/assetReferences'
import IconPackage from '@/components/icons/IconPackage.vue'
import IconFolder from '@/components/icons/IconFolder.vue'
import IconChat from '@/components/icons/IconChat.vue'

/**
 * MentionPopover — @ 提及下拉（web.md §十二）
 *
 * - 数据源：后端 canonical Asset Reference 分页搜索
 * - 键盘：↑↓ 选择 / Enter 确认 / Esc 取消
 * - 列表项：图标 + 文本 + 类型副标签
 */
const props = defineProps<{
  open: boolean
  query: string
  excludedReferenceKeys: string[]
}>()

const emit = defineEmits<{
  'update:open': [v: boolean]
  select: [candidate: AssetReferenceCandidate]
}>()

const activeIndex = ref(0)
const results = ref<AssetReferenceCandidate[]>([])
const loading = ref(false)
let generation = 0
let controller: AbortController | null = null

function iconFor(kind: AssetReferenceCandidate['kind']) {
  switch (kind) {
    case 'PACKAGE':
      return IconPackage
    case 'IMAGE':
      return IconChat
    default:
      return IconFolder
  }
}

watch(
  () => [props.open, props.query, props.excludedReferenceKeys.join('\n')] as const,
  () => { void load() },
  { immediate: true }
)

async function load(): Promise<void> {
  const current = ++generation
  controller?.abort()
  if (!props.open) {
    results.value = []
    loading.value = false
    return
  }
  controller = new AbortController()
  loading.value = true
  try {
    const candidates = await searchAssetReferences(props.query, props.excludedReferenceKeys, controller.signal)
    if (current === generation) {
      results.value = candidates
      activeIndex.value = 0
    }
  } catch {
    if (current === generation && !controller.signal.aborted) results.value = []
  } finally {
    if (current === generation) loading.value = false
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
    :open="open"
    @update:open="(v: boolean) => $emit('update:open', v)"
  >
    <template #trigger>
      <slot name="trigger" />
    </template>
    <AppScrollArea max-height="240px">
      <div
        v-if="loading"
        class="empty-hint"
      >
        正在加载...
      </div>
      <div
        v-else-if="results.length === 0"
        class="empty-hint"
      >
        无匹配项
      </div>
      <button
        v-for="(node, idx) in results"
        :key="node.referenceKey"
        type="button"
        :class="[
          'mention-item flex items-center gap-2 w-full text-left px-2 py-1.5 rounded-sm text-sm',
          idx === activeIndex ? 'bg-accent-soft text-fg-primary' : 'text-fg-primary hover:bg-bg-sunken'
        ]"
        @click="emit('select', node)"
        @mouseenter="activeIndex = idx"
      >
        <component
          :is="iconFor(node.kind)"
          :size="16"
          class="text-fg-secondary shrink-0"
        />
        <span class="flex-1 truncate">{{ node.displayPath }}</span>
        <span
          class="text-xs text-fg-muted shrink-0"
        >{{ node.sourceGroup === 'MATERIALS' ? '素材' : '产物' }}</span>
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
