<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import IconPinOff from '@/components/icons/IconPinOff.vue'
import IconChevronRight from '@/components/icons/IconChevronRight.vue'
import AppButton from '@/components/ui/AppButton.vue'
import IconPlus from '@/components/icons/IconPlus.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconPackage from '@/components/icons/IconPackage.vue'
import IconMoreHorizontal from '@/components/icons/IconMoreHorizontal.vue'
import AppAvatar from '@/components/ui/AppAvatar.vue'
import AppDropdownMenu from '@/components/ui/AppDropdownMenu.vue'
import AppDropdownMenuItem from '@/components/ui/AppDropdownMenuItem.vue'
import HistoryList from '@/components/files/HistoryList.vue'

/**
 * LeftPanel — 左栏导航（frontend/shell-routing-auth.md / product.md）
 *
 * 三层地基：侧栏落 bg-sunken，导航项 hover 落 panel 白，active 落 accent-soft。
 *
 * 状态：
 * - 钉住（默认）：280px 常驻
 * - 未钉住：48px 图标导轨，导航仍可直达；点展开按钮恢复钉住
 * - 无 hover 浮出：展开/收起只由显式点击驱动
 */

const ui = useUiStore()
const auth = useAuthStore()
const toast = useToastStore()
const router = useRouter()
const route = useRoute()

const displayName = computed(() => auth.user?.displayName || auth.user?.username || '未登录')
const userInitial = computed(() => displayName.value[0] ?? 'U')

const navItems = [
  { to: '/chat/new', label: '会话', icon: IconChat, match: (p: string) => p.startsWith('/chat') },
  { to: '/materials', label: '素材', icon: IconPackage, match: (p: string) => p.startsWith('/materials') },
  { to: '/outputs', label: '产物', icon: IconImage, match: (p: string) => p.startsWith('/outputs') },
] as const

function isActive(match: (p: string) => boolean): boolean {
  return match(route.path)
}

async function handleLogout(): Promise<void> {
  await auth.logout()
  toast.push({ variant: 'success', message: '已退出登录' })
}

function startNewChat(): void {
  void router.push('/chat/new').catch(showNavigationError)
}

function showNavigationError(error: unknown): void {
  toast.push({ variant: 'danger', message: error instanceof Error ? error.message : '导航失败' })
}
</script>

<template>
  <aside
    class="left-panel flex flex-col"
    :class="{ collapsed: !ui.leftPanelPinned }"
  >
    <!-- 收起态：48px 图标导轨 -->
    <div
      v-if="!ui.leftPanelPinned"
      class="rail flex flex-col items-center"
    >
      <button
        type="button"
        class="rail-icon-btn mt-2"
        aria-label="展开导航"
        title="展开导航"
        @click="ui.toggleLeftPanelPin()"
      >
        <IconChevronRight :size="16" />
      </button>

      <nav class="mt-3 flex flex-col items-center gap-1">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="rail-icon-btn"
          :class="{ active: isActive(item.match) }"
          :aria-label="item.label"
          :title="item.label"
        >
          <component
            :is="item.icon"
            :size="16"
          />
        </RouterLink>
      </nav>

      <div class="rail-footer mt-auto mb-2">
        <AppDropdownMenu>
          <template #trigger>
            <button
              type="button"
              class="rail-avatar"
              :aria-label="`${displayName} 的菜单`"
              :title="displayName"
            >
              <AppAvatar
                :text="userInitial"
                :size="26"
              />
            </button>
          </template>
          <AppDropdownMenuItem
            danger
            @select="handleLogout"
          >
            退出登录
          </AppDropdownMenuItem>
        </AppDropdownMenu>
      </div>
    </div>

    <!-- 展开态：280px 导航 -->
    <div
      v-else
      class="panel-body flex h-full w-[280px] flex-col"
    >
      <!-- 品牌 + 钉住控制 -->
      <div class="flex items-center justify-between px-4 pb-1 pt-3">
        <div class="flex items-center gap-2">
          <IconPackage
            :size="20"
            class="text-accent"
          />
          <span class="text-md font-semibold text-fg-primary">PixFlow</span>
        </div>
        <button
          type="button"
          class="icon-btn"
          aria-label="收起导航"
          title="收起导航"
          @click="ui.toggleLeftPanelPin()"
        >
          <IconPinOff :size="15" />
        </button>
      </div>

      <!-- 新对话 -->
      <div class="px-3 pb-2 pt-2">
        <AppButton
          variant="secondary"
          class="w-full justify-start"
          @click="startNewChat"
        >
          <IconPlus :size="16" />
          新对话
        </AppButton>
      </div>

      <!-- 主导航 -->
      <nav
        class="flex flex-col gap-0.5 px-3 pb-2"
        aria-label="主导航"
      >
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          :class="{ active: isActive(item.match) }"
        >
          <component
            :is="item.icon"
            :size="16"
            class="shrink-0"
          />
          <span class="truncate">{{ item.label }}</span>
        </RouterLink>
      </nav>

      <!-- 会话历史 -->
      <div class="history-section mt-1 flex-1 overflow-y-auto border-t border-border pt-2">
        <HistoryList />
      </div>

      <!-- 用户菜单 -->
      <div class="mt-auto border-t border-border p-3">
        <AppDropdownMenu>
          <template #trigger>
            <button
              type="button"
              class="user-trigger"
              :aria-label="`${displayName} 的菜单`"
            >
              <AppAvatar
                :text="userInitial"
                :size="28"
              />
              <span class="flex-1 truncate text-left text-sm font-medium text-fg-primary">{{ displayName }}</span>
              <IconMoreHorizontal
                :size="16"
                class="text-fg-muted"
              />
            </button>
          </template>
          <AppDropdownMenuItem
            danger
            @select="handleLogout"
          >
            退出登录
          </AppDropdownMenuItem>
        </AppDropdownMenu>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.left-panel {
  background: var(--bg-sunken);
  border-right: 1px solid var(--border);
  width: 280px;
  flex-shrink: 0;
  height: 100%;
}
.left-panel.collapsed {
  width: 48px;
}

/* 收起/展开切换：宽度瞬时切换（避免布局属性动画），内容淡入衔接 */
.rail,
.panel-body {
  animation: panel-fade-in var(--dur-fast) var(--ease-out);
}
@keyframes panel-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

/* 收起态导轨 */
.rail {
  width: 48px;
  height: 100%;
}
.rail-icon-btn {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  background: transparent;
  border: none;
  color: var(--fg-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.rail-icon-btn:hover {
  background: var(--bg-panel);
  color: var(--fg-primary);
}
.rail-icon-btn.active {
  background: var(--accent-soft);
  color: var(--fg-primary);
}
.rail-avatar {
  background: transparent;
  border: none;
  padding: 0;
  cursor: pointer;
  border-radius: 9999px;
  display: flex;
}

/* 主导航链接 */
.nav-link {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  color: var(--fg-secondary);
  font-size: 14px;
  text-decoration: none;
  transition: background-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.nav-link:hover {
  background-color: var(--bg-panel);
  color: var(--fg-primary);
}
.nav-link.active {
  background-color: var(--accent-soft);
  color: var(--fg-primary);
  font-weight: 500;
}

.icon-btn {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: transparent;
  border: none;
  color: var(--fg-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.icon-btn:hover {
  background: var(--bg-panel);
  color: var(--fg-primary);
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border-radius: 6px;
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease-out);
}
.user-trigger:hover {
  background: var(--bg-panel);
}
</style>
