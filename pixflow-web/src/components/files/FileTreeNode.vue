<script setup lang="ts">
import IconPackage from '@/components/icons/IconPackage.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconFolder from '@/components/icons/IconFolder.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconMoreHorizontal from '@/components/icons/IconMoreHorizontal.vue'
import AppDropdownMenu from '@/components/ui/AppDropdownMenu.vue'
import AppDropdownMenuItem from '@/components/ui/AppDropdownMenuItem.vue'
import type { FileIndexNode } from '@/stores/fileIndex'

/**
 * FileTreeNode — 单个树节点（web.md §7.3 FileTreeNode）
 *
 * - 图标 16px text-fg-secondary
 * - 文本 text-sm
 * - hover 显示 AppDropdownMenu（重命名 / 删除 / 在新窗口打开（仅文件夹））
 * - 选中态 bg-accent-soft
 */
const props = withDefaults(
  defineProps<{
    node: FileIndexNode
    depth?: number
    selected?: boolean
  }>(),
  {
    depth: 0,
    selected: false,
  }
)

const emit = defineEmits<{
  select: [node: FileIndexNode]
  rename: [node: FileIndexNode]
  remove: [node: FileIndexNode]
  openExternal: [node: FileIndexNode]
}>()

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
</script>

<template>
  <div
    class="file-tree-node group flex items-center gap-2 rounded-sm px-2 py-1.5 text-sm cursor-pointer"
    :class="selected ? 'bg-accent-soft text-fg-primary' : 'text-fg-primary hover:bg-bg-sunken'"
    :style="{ paddingLeft: `${8 + depth * 12}px` }"
    @click="emit('select', node)"
  >
    <component :is="iconFor(node.type)" :size="16" class="text-fg-secondary shrink-0" />
    <span class="flex-1 truncate">{{ node.label }}</span>

    <AppDropdownMenu class="opacity-0 group-hover:opacity-100 transition-opacity">
      <template #trigger>
        <button
          type="button"
          class="p-1 rounded-sm text-fg-muted hover:bg-bg-sunken hover:text-fg-primary"
          aria-label="更多操作"
          @click.stop
        >
          <IconMoreHorizontal :size="14" />
        </button>
      </template>
      <AppDropdownMenuItem @select="emit('rename', node)">重命名</AppDropdownMenuItem>
      <AppDropdownMenuItem
        v-if="node.type === 'conversation'"
        @select="emit('openExternal', node)"
      >
        在新窗口打开
      </AppDropdownMenuItem>
      <AppDropdownMenuItem danger @select="emit('remove', node)">删除</AppDropdownMenuItem>
    </AppDropdownMenu>
  </div>
</template>