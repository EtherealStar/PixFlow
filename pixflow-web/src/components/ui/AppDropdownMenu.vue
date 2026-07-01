<script setup lang="ts">
import {
  DropdownMenuRoot,
  DropdownMenuTrigger,
  DropdownMenuPortal,
  DropdownMenuContent,
} from 'radix-vue'

/**
 * AppDropdownMenu — 下拉菜单（包装 radix-vue DropdownMenu）
 *
 * 视觉（web.md §7.2）：
 * - content: bg-bg-panel shadow-lg rounded-md p-1 min-w-[160px]
 *
 * 用法：
 *   <AppDropdownMenu>
 *     <template #trigger>
 *       <AppButton variant="ghost">...</AppButton>
 *     </template>
 *     <AppDropdownMenuItem @select="onRename">重命名</AppDropdownMenuItem>
 *     <AppDropdownMenuSeparator />
 *     <AppDropdownMenuItem danger @select="onDelete">删除</AppDropdownMenuItem>
 *   </AppDropdownMenu>
 */
</script>

<template>
  <DropdownMenuRoot>
    <DropdownMenuTrigger as-child>
      <slot name="trigger" />
    </DropdownMenuTrigger>
    <DropdownMenuPortal>
      <DropdownMenuContent
        :side-offset="4"
        align="end"
        class="z-50 min-w-[160px] bg-bg-panel shadow-lg rounded-md p-1 border border-border"
      >
        <slot />
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>
</template>