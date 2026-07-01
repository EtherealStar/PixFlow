<script setup lang="ts">
/**
 * IconBase — 所有自绘图标的统一容器
 *
 * 唯一权威来源：docs/design-docs/web.md §7.4
 *
 * 设计约束（web.md §5.1）：
 * - 24×24 viewBox，stroke=1.75（与 lucide 风格一致）
 * - stroke="currentColor"：通过父级 text-* 控制颜色，便于 hover / disabled 联动
 * - fill="none" + round line cap/join：保证线条柔和不锐利
 * - aria-hidden="true" 默认：图标本身是装饰元素；如需语义请在父级加 aria-label
 *
 * 用法：
 *   <IconBase>
 *     <path d="..." />
 *   </IconBase>
 *
 *   <IconBase :size="20" stroke-width="2">
 *     <circle cx="12" cy="12" r="10" />
 *   </IconBase>
 */
withDefaults(
  defineProps<{
    /** 图标大小（px），默认 24。允许 16 / 20 / 24 三档。 */
    size?: number
    /** 描边粗细，默认 1.75（lucide 风格）。 */
    strokeWidth?: number
    /** 屏幕阅读器隐藏（默认 true，即纯装饰）。 */
    ariaHidden?: boolean
    /** 额外 class，用于覆盖默认颜色 / 间距。 */
    class?: string
  }>(),
  {
    size: 24,
    strokeWidth: 1.75,
    ariaHidden: true,
    class: '',
  }
)
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    :aria-hidden="ariaHidden"
    :class="$props.class"
  >
    <slot />
  </svg>
</template>