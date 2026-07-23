import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

/**
 * UI 状态机（frontend/shell-routing-auth.md / frontend/product.md）
 *
 * - leftPanelPinned / rightPanelPinned: 面板钉住状态（非敏感 UI 偏好，持久化 localStorage）
 * - rightPanelExpanded: 右栏展开态（仅用户显式展开；新活动永不自动展开）
 * - networkOnline: 网络在线状态（影响顶部 NetworkBanner 显隐）
 *
 * 注：trace ID 只作为失败操作的可复制错误编号出现，没有全局 trace 组件。
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
  const networkOnline = ref(true)

  const initial = readPinnedFromLs()
  const leftPanelPinned = ref(initial.left)
  const rightPanelPinned = ref(initial.right)
  const rightPanelExpanded = ref(false)

  // 持久化 pinned 状态
  watch([leftPanelPinned, rightPanelPinned], ([l, r]) => {
    writePinnedToLs({ left: l, right: r })
  })

  function toggleLeftPanelPin(): void { leftPanelPinned.value = !leftPanelPinned.value }
  function toggleRightPanelPin(): void {
    rightPanelPinned.value = !rightPanelPinned.value
    if (rightPanelPinned.value) rightPanelExpanded.value = true
  }
  function setRightPanelExpanded(v: boolean): void {
    if (rightPanelPinned.value) return // 钉住态不允许折叠
    rightPanelExpanded.value = v
  }

  return {
    networkOnline,
    leftPanelPinned,
    rightPanelPinned,
    rightPanelExpanded,
    toggleLeftPanelPin,
    toggleRightPanelPin,
    setRightPanelExpanded,
  }
})
