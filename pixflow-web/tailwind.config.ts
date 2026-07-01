import type { Config } from 'tailwindcss'

/**
 * Tailwind 设计令牌（design tokens）
 * 唯一权威来源：docs/design-docs/web.md §5.2
 *
 * 设计原则（web.md §5.1）：
 * - 三层背景递进：page → sunken → panel
 * - 暖灰基调（stone 系列），不混 gray/zinc/neutral
 * - 主色克制：accent (indigo) 仅用于主操作
 * - 阴影统一带 28,25,23 暖灰基调 + 6%~8% 透明度，禁止纯黑
 * - 圆角仅四档：sm 6 / md 10 / lg 14 / xl 20
 * - 仅浅色主题（darkMode 显式禁用）
 */
export default {
  // 仅浅色主题（web.md §二十三 决策），显式禁用暗色分支。
  darkMode: false,
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        // 三层背景（page / sunken / panel）
        bg: {
          page: '#FAFAF9', // stone-50
          sunken: '#F5F5F4', // stone-100
          panel: '#FFFFFF',
        },
        // 边框（浅深两档）
        border: {
          DEFAULT: '#E7E5E4', // stone-200
          strong: '#D6D3D1', // stone-300
        },
        // 文本（四档语义）
        fg: {
          primary: '#1C1917', // stone-900
          secondary: '#57534E', // stone-600
          muted: '#A8A29E', // stone-400
          inverse: '#FFFFFF',
        },
        // 主色（indigo，仅用于主操作）
        accent: {
          DEFAULT: '#4F46E5', // indigo-600
          hover: '#6366F1', // indigo-500
          soft: '#EEF2FF', // indigo-50（hover 背景）
        },
        // 状态色
        success: { DEFAULT: '#10B981', soft: '#ECFDF5' },
        warning: { DEFAULT: '#F59E0B', soft: '#FFFBEB' },
        danger: { DEFAULT: '#EF4444', soft: '#FEF2F2' },
        info: { DEFAULT: '#3B82F6', soft: '#EFF6FF' },
      },
      borderRadius: {
        sm: '6px',
        md: '10px',
        lg: '14px',
        xl: '20px',
      },
      boxShadow: {
        sm: '0 1px 2px rgba(28,25,23,0.04), 0 1px 3px rgba(28,25,23,0.06)',
        md: '0 4px 12px rgba(28,25,23,0.06), 0 2px 4px rgba(28,25,23,0.04)',
        lg: '0 12px 32px rgba(28,25,23,0.08), 0 4px 8px rgba(28,25,23,0.04)',
      },
      fontFamily: {
        sans: [
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'PingFang SC',
          'Microsoft YaHei',
          'sans-serif',
        ],
      },
      fontSize: {
        xs: ['12px', { lineHeight: '1.5' }],
        sm: ['13px', { lineHeight: '1.5' }],
        base: ['14px', { lineHeight: '1.5' }],
        md: ['15px', { lineHeight: '1.5' }],
        lg: ['17px', { lineHeight: '1.4' }],
        xl: ['20px', { lineHeight: '1.35' }],
        '2xl': ['24px', { lineHeight: '1.3' }],
        '3xl': ['30px', { lineHeight: '1.25' }],
      },
      spacing: {
        '4.5': '18px',
        '5.5': '22px',
      },
    },
  },
} satisfies Config
