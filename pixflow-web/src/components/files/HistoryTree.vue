<script setup lang="ts">
import { computed } from 'vue'
import FileTree from '@/components/chat/FileTree.vue'
import { useFileIndexStore, type FileIndexNode } from '@/stores/fileIndex'
import { useConversationsStore } from '@/stores/conversations'
import { useToastStore } from '@/stores/toast'
import { useRouter } from 'vue-router'

/**
 * HistoryTree — 左栏"历史会话"段（web.md §七 / §二十）
 *
 * 数据源：useFileIndexStore.historyNodes。
 * 行为：选中跳 /chat/:cid；删除归档会话。
 */
const fileIndex = useFileIndexStore()
const conversations = useConversationsStore()
const toast = useToastStore()
const router = useRouter()

const nodes = computed(() => fileIndex.historyNodes)

function onSelect(node: FileIndexNode): void {
  if (node.type === 'history') {
    void router.push(`/chat/${node.refId}`).catch((error: unknown) => {
      toast.push({ variant: 'danger', message: error instanceof Error ? error.message : '导航失败' })
    })
  }
}

function onRename(node: FileIndexNode): void {
  // R6 接入 RenameDialog；此处占位
  const next = window.prompt('重命名为：', node.label)
  if (!next || next === node.label) return
  toast.push({ variant: 'success', message: '已重命名' })
}

function onRemove(node: FileIndexNode): void {
  if (node.type !== 'history') return
  const ok = window.confirm(`确认删除「${node.label}」？`)
  if (!ok) return
  void conversations.archive(String(node.refId))
  toast.push({ variant: 'info', message: '已删除' })
}
</script>

<template>
  <FileTree
    :nodes="nodes"
    @select="onSelect"
    @rename="onRename"
    @remove="onRemove"
  />
</template>
