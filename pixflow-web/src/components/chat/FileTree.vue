<script setup lang="ts">
import FileTreeNode from '@/components/files/FileTreeNode.vue'
import type { FileIndexNode } from '@/stores/fileIndex'

/**
 * FileTree — 文件树纯展示组件（web.md §7.3）
 *
 * 与左栏 FileTreePanel 和 @提及下拉共用；本身不发请求，只渲染传入的节点列表。
 */
const props = withDefaults(
  defineProps<{
    nodes: FileIndexNode[]
    selectedId?: string | null
  }>(),
  {
    selectedId: null,
  }
)

const emit = defineEmits<{
  select: [node: FileIndexNode]
  rename: [node: FileIndexNode]
  remove: [node: FileIndexNode]
  openExternal: [node: FileIndexNode]
}>()
</script>

<template>
  <div class="file-tree flex flex-col gap-0.5">
    <FileTreeNode
      v-for="node in nodes"
      :key="node.id"
      :node="node"
      :selected="node.id === selectedId"
      @select="emit('select', $event)"
      @rename="emit('rename', $event)"
      @remove="emit('remove', $event)"
      @open-external="emit('openExternal', $event)"
    />
  </div>
</template>