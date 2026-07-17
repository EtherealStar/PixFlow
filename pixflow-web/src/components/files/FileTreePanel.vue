<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import FileTree from '@/components/chat/FileTree.vue'
import { useFileIndexStore, type FileIndexNode } from '@/stores/fileIndex'
import { usePackagesStore } from '@/stores/packages'
import { useConversationsStore } from '@/stores/conversations'
import { useToastStore } from '@/stores/toast'

/**
 * FileTreePanel — 左栏文件树（web.md §九）
 *
 * section="upload" 渲染"上传"段；section="results" 渲染"结果"段。
 * 数据源：useFileIndexStore（由 packages + conversations 派生）。
 */
const props = defineProps<{
  section: 'upload' | 'results'
}>()

const router = useRouter()
const fileIndex = useFileIndexStore()
const packages = usePackagesStore()
const conversations = useConversationsStore()
const toast = useToastStore()

const nodes = computed(() => (props.section === 'upload' ? fileIndex.uploadNodes : fileIndex.resultNodes))

function onSelect(node: FileIndexNode): void {
  if (node.type === 'package') {
    void router.push(`/files?folder=${node.refId}`).catch(showNavigationError)
  } else {
    void router.push(`/chat/${node.refId}`).catch(showNavigationError)
  }
}

function showNavigationError(error: unknown): void {
  toast.push({ variant: 'danger', message: error instanceof Error ? error.message : '导航失败' })
}

function onRename(node: FileIndexNode): void {
  // R6 阶段接入 RenameDialog；这里先给出最小可用交互
  const next = window.prompt('重命名为：', node.label)
  if (!next || next === node.label) return
  if (node.type === 'package') {
    const p = packages.get(Number(node.refId))
    if (p) packages.upsert({ ...p, name: next })
  }
  toast.push({ variant: 'success', message: '已重命名' })
}

function onRemove(node: FileIndexNode): void {
  const ok = window.confirm(`确认删除「${node.label}」？`)
  if (!ok) return
  if (node.type === 'conversation' || node.type === 'history') {
    void conversations.archive(String(node.refId))
  }
  toast.push({ variant: 'info', message: '已删除' })
}

function onOpenExternal(node: FileIndexNode): void {
  window.open(`/chat/${node.refId}`, '_blank')
}
</script>

<template>
  <FileTree
    :nodes="nodes"
    @select="onSelect"
    @rename="onRename"
    @remove="onRemove"
    @open-external="onOpenExternal"
  />
</template>
