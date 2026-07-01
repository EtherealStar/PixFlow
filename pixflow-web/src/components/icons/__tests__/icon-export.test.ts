import { describe, expect, it } from 'vitest'
import * as Icons from '../index'
import { ICON_NAMES } from '../index'

/**
 * 图标系统导出完整性测试
 *
 * 验证：
 * - 所有 web.md §7.4 列出的 25 个图标都已被导出
 * - 数量与 ICON_NAMES 数组一致（防止遗漏）
 */
describe('icons 统一出口', () => {
  it('导出 ICON_NAMES 数组中列出的所有图标', () => {
    // 8 个自绘核心 + 19 个 lucide 转发 = 27 个（web.md §7.4 完整清单）
    expect(ICON_NAMES.length).toBe(27)
    for (const name of ICON_NAMES) {
      expect(Icons).toHaveProperty(name)
      expect(Icons[name]).toBeDefined()
    }
  })

  it('导出 IconBase 容器组件', () => {
    expect(Icons.IconBase).toBeDefined()
  })
})