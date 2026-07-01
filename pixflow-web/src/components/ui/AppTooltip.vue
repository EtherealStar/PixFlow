<script setup lang="ts">
import { TooltipRoot, TooltipTrigger, TooltipPortal, TooltipContent, TooltipArrow } from 'radix-vue'

/**
 * AppTooltip — hover 提示（包装 radix-vue Tooltip）
 *
 * 视觉（web.md §7.2）：
 * - bg-fg-primary text-fg-inverse
 * - rounded-sm px-2 py-1 text-xs
 * - delayDuration 200ms（hover 200ms 后显示）
 */
withDefaults(
  defineProps<{
    /** 提示文本 */
    content: string
    /** 显示侧（top / bottom / left / right） */
    side?: 'top' | 'bottom' | 'left' | 'right'
    /** 延迟显示 ms */
    delay?: number
  }>(),
  {
    side: 'top',
    delay: 200,
  }
)
</script>

<template>
  <TooltipRoot :delay-duration="delay">
    <TooltipTrigger as-child>
      <slot />
    </TooltipTrigger>
    <TooltipPortal>
      <TooltipContent
        :side="side"
        :side-offset="6"
        class="z-50 bg-fg-primary text-fg-inverse rounded-sm px-2 py-1 text-xs shadow-md select-none"
      >
        {{ content }}
        <TooltipArrow class="fill-fg-primary" :width="8" :height="4" />
      </TooltipContent>
    </TooltipPortal>
  </TooltipRoot>
</template>