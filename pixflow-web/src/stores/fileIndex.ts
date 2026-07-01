import { defineStore } from 'pinia'
import { computed } from 'vue'
import { usePackagesStore } from './packages'
import { useConversationsStore } from './conversations'

/**
 * 左栏文件树索引（web.md §九 / §十二 / §二十）
 *
 * 数据源：由 usePackagesStore + useConversationsStore 派生，不重复缓存原始数据。
 *
 * 节点类型：
 * - package: 上传中/已上传的素材包（IconPackage）
 * - conversation: 结果段的会话节点（IconChat）
 * - history: 历史会话（IconChat）
 *
 * 用途：
 * - 左栏"上传"/"结果"/"历史会话"三段展示
 * - Composer 的 @ 提及下拉搜索（本地模糊匹配，不发请求）
 */

export type FileIndexNodeType = 'package' | 'conversation' | 'history'

export interface FileIndexNode {
  id: string
  type: FileIndexNodeType
  label: string
  /** 类型副标签，如"压缩包 · 12.4MB" */
  subtitle?: string
  /** 原始关联 id（packageId 或 conversationId） */
  refId: string | number
}

export const useFileIndexStore = defineStore('fileIndex', () => {
  const packages = usePackagesStore()
  const conversations = useConversationsStore()

  // "上传"段：素材包节点
  const uploadNodes = computed<FileIndexNode[]>(() => {
    const nodes: FileIndexNode[] = []
    for (const p of packages.items.values()) {
      nodes.push({
        id: `package-${p.packageId}`,
        type: 'package',
        label: p.name,
        subtitle: `压缩包 · ${p.status}`,
        refId: p.packageId,
      })
    }
    return nodes
  })

  // "结果"段：已归档的会话（关联了 packageId 的对话）
  const resultNodes = computed<FileIndexNode[]>(() => {
    return conversations.items
      .filter((c) => c.packageId != null)
      .map((c) => ({
        id: `conversation-${c.conversationId}`,
        type: 'conversation' as const,
        label: c.title ?? `会话 ${c.conversationId.slice(0, 8)}`,
        subtitle: '会话',
        refId: c.conversationId,
      }))
  })

  // "历史会话"段：全部会话（不含 packageId 过滤）
  const historyNodes = computed<FileIndexNode[]>(() => {
    return conversations.items.map((c) => ({
      id: `history-${c.conversationId}`,
      type: 'history' as const,
      label: c.title ?? `会话 ${c.conversationId.slice(0, 8)}`,
      refId: c.conversationId,
    }))
  })

  /** @提及本地模糊搜索：跨"上传"段与"结果"段，不区分大小写。 */
  function search(query: string): FileIndexNode[] {
    const q = query.trim().toLowerCase()
    const all = [...uploadNodes.value, ...resultNodes.value]
    if (!q) return all.slice(0, 20)
    return all.filter((n) => n.label.toLowerCase().includes(q)).slice(0, 20)
  }

  async function refresh(): Promise<void> {
    await conversations.refresh()
  }

  return { uploadNodes, resultNodes, historyNodes, search, refresh }
})