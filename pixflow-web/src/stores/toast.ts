import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { AppToastVariant } from '@/types/ui'

/**
 * Toast 状态机（web.md §二十二 错误处理）
 *
 * - 全局单例，多页面共用
 * - 默认 4s 自动消失
 * - 同 variant 同 message 1s 内不重复（去重防骚扰）
 */

export interface ToastItem {
  id: string
  variant: AppToastVariant
  message: string
  /** 自动消失 ms（0 = 不自动消失） */
  duration: number
  /** 创建时间戳 */
  createdAt: number
}

export const useToastStore = defineStore('toast', () => {
  const items = ref<ToastItem[]>([])

  function push(input: { variant?: AppToastVariant; message: string; duration?: number }): string {
    const variant = input.variant ?? 'info'
    const duration = input.duration ?? 4000
    const now = Date.now()

    // 1s 内同 variant 同 message 去重
    const lastSame = items.value.find(
      (i) => i.variant === variant && i.message === input.message && now - i.createdAt < 1000
    )
    if (lastSame) return lastSame.id

    const id = `t-${now}-${Math.random().toString(36).slice(2, 8)}`
    items.value.push({
      id,
      variant,
      message: input.message,
      duration,
      createdAt: now,
    })
    if (duration > 0) {
      setTimeout(() => dismiss(id), duration)
    }
    return id
  }

  function dismiss(id: string): void {
    const idx = items.value.findIndex((i) => i.id === id)
    if (idx >= 0) items.value.splice(idx, 1)
  }

  function clear(): void {
    items.value.splice(0)
  }

  return { items, push, dismiss, clear }
})