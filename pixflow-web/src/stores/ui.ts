import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAgentTurnsStore } from './agentTurns'

/**
 * UI 状态机（web.md §六 / §九 / §十）
 *
 * - leftPanelPinned: 左栏钉住状态（持久化到 localStorage）
 * - rightPanelPinned: 右栏钉住状态（持久化到 localStorage）
 * - rightPanelExpanded: 右栏展开态（新任务出现时自动展开 6s 后收回；用户手动展开或钉住转常驻）
 * - sidebarOpen: 旧版兼容字段（AppLayout 还在用，新布局用 leftPanelPinned）
 * - floatingTraceId: 右下角浮窗 traceId
 * - networkOnline: 网络在线状态（影响顶部 NetworkBanner 显隐）
 */

const PINNED_LS_KEY = 'pixflow.ui.panelPinned'

function readPinnedFromLs(): { left: boolean; right: boolean } {
  try {
    const raw = localStorage.getItem(PINNED_LS_KEY)
    if (!raw) return { left: true, right: false }
    const obj = JSON.parse(raw) as { left?: boolean; right?: boolean }
    return { left: !!obj.left, right: !!obj.right }
  } catch {
    return { left: true, right: false }
  }
}

function writePinnedToLs(p: { left: boolean; right: boolean }): void {
  try {
    localStorage.setItem(PINNED_LS_KEY, JSON.stringify(p))
  } catch {
    // ignore
  }
}

export const useUiStore = defineStore('ui', () => {
  const sidebarOpen = ref(true) // 旧字段保留（AppLayout R1 过渡用）
  const floatingTraceId = ref<string | null>(null)
  const networkOnline = ref(true)

  // 新版布局状态
  const initial = readPinnedFromLs()
  const leftPanelPinned = ref(initial.left)
  const rightPanelPinned = ref(initial.right)
  const rightPanelExpanded = ref(false)

  // 持久化 pinned 状态
  watch([leftPanelPinned, rightPanelPinned], ([l, r]) => {
    writePinnedToLs({ left: l, right: r })
  })

  // 自动收回定时器
  let expandTimer: ReturnType<typeof setTimeout> | null = null
  function autoExpandRightPanel(ms = 6000): void {
    if (rightPanelPinned.value) return // 钉住态不自动收回
    rightPanelExpanded.value = true
    if (expandTimer) clearTimeout(expandTimer)
    expandTimer = setTimeout(() => {
      rightPanelExpanded.value = false
      expandTimer = null
    }, ms)
  }

  // agentTurns 联动 traceId
  const agentTurns = useAgentTurnsStore()
  watch(() => agentTurns.lastTraceId, (v) => {
    if (v) floatingTraceId.value = v
  })

  function toggleSidebar(): void { sidebarOpen.value = !sidebarOpen.value }
  function toggleLeftPanelPin(): void { leftPanelPinned.value = !leftPanelPinned.value }
  function toggleRightPanelPin(): void {
    rightPanelPinned.value = !rightPanelPinned.value
    if (rightPanelPinned.value) rightPanelExpanded.value = true
  }
  function setRightPanelExpanded(v: boolean): void {
    if (rightPanelPinned.value) return // 钉住态不允许折叠
    rightPanelExpanded.value = v
    if (expandTimer) { clearTimeout(expandTimer); expandTimer = null }
  }

  return {
    sidebarOpen,
    floatingTraceId,
    networkOnline,
    leftPanelPinned,
    rightPanelPinned,
    rightPanelExpanded,
    toggleSidebar,
    toggleLeftPanelPin,
    toggleRightPanelPin,
    setRightPanelExpanded,
    autoExpandRightPanel,
  }
})