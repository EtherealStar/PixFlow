<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import IconPin from '@/components/icons/IconPin.vue'
import IconPinOff from '@/components/icons/IconPinOff.vue'
import AppButton from '@/components/ui/AppButton.vue'
import IconPlus from '@/components/icons/IconPlus.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconPackage from '@/components/icons/IconPackage.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconMoreHorizontal from '@/components/icons/IconMoreHorizontal.vue'
import AppAvatar from '@/components/ui/AppAvatar.vue'
import AppDropdownMenu from '@/components/ui/AppDropdownMenu.vue'
import AppDropdownMenuItem from '@/components/ui/AppDropdownMenuItem.vue'
import AppDropdownMenuSeparator from '@/components/ui/AppDropdownMenuSeparator.vue'
import HistoryList from '@/components/files/HistoryList.vue'

/**
 * LeftPanel — 左栏
 *
 * 状态：
 * - leftPanelPinned: 钉住常驻（持久化）
 * - hover 时浮出 280px 浮层（仅未钉住时）
 */

const ui = useUiStore()
const auth = useAuthStore()
const toast = useToastStore()
const router = useRouter()

const hovering = ref(false)
let hoverTimer: ReturnType<typeof setTimeout> | null = null

function onEnter(): void {
  if (ui.leftPanelPinned) return
  hovering.value = true
  if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null }
}
function onLeave(): void {
  if (ui.leftPanelPinned) return
  hoverTimer = setTimeout(() => { hovering.value = false; hoverTimer = null }, 1500)
}

const effectiveExpanded = computed(() => ui.leftPanelPinned || hovering.value)

const displayName = computed(() => auth.user?.displayName || auth.user?.username || '未登录')
const userInitial = computed(() => displayName.value[0] ?? 'U')

async function handleLogout(): Promise<void> {
  await auth.logout()
  toast.push({ variant: 'success', message: '已退出登录' })
}

function startNewChat(): void {
  router.push('/')
}
</script>

<template>
  <aside
    class="left-panel flex flex-col"
    :class="{ collapsed: !effectiveExpanded }"
    @mouseenter="onEnter"
    @mouseleave="onLeave"
  >
    <!-- 把手 -->
    <div v-if="!effectiveExpanded" class="rail">
      <button
        type="button"
        class="rail-pin-btn"
        :aria-label="ui.leftPanelPinned ? '取消钉住' : '钉住面板'"
        @click="ui.toggleLeftPanelPin()"
      >
        <IconPin v-if="!ui.leftPanelPinned" :size="20" />
        <IconPinOff v-else :size="20" />
      </button>
    </div>

    <!-- 展开态 -->
    <div v-show="effectiveExpanded" class="panel-body flex flex-col h-full w-[300px]">
      <!-- 品牌 Logo -->
      <div class="px-4 pt-4 pb-2 flex items-center gap-2">
        <IconPackage :size="24" class="text-accent" />
        <span class="text-md font-semibold text-fg-primary">PixFlow</span>
      </div>

      <!-- 顶部新对话按钮与钉住控制 -->
      <div class="px-3 pt-2 pb-3 flex items-center justify-between">
        <AppButton variant="ghost" class="new-chat-btn flex-1 justify-start border border-border bg-bg-panel hover:bg-bg-sunken shadow-sm rounded-md h-9" @click="startNewChat">
          <IconPlus :size="16" class="mr-2" />
          新对话
        </AppButton>
        <button
          type="button"
          class="pin-btn ml-2"
          :aria-label="ui.leftPanelPinned ? '取消钉住' : '钉住面板'"
          @click="ui.toggleLeftPanelPin()"
        >
          <IconPin v-if="!ui.leftPanelPinned" :size="16" />
          <IconPinOff v-else :size="16" />
        </button>
      </div>

      <!-- 主导航链接 -->
      <nav class="main-nav px-3 flex flex-col gap-1 pb-2">
        <RouterLink to="/" class="nav-link" active-class="active" exact>
          <IconChat :size="16" class="shrink-0" />
          <span class="truncate">会话</span>
        </RouterLink>
        <RouterLink to="/files" class="nav-link" active-class="active" exact>
          <IconPackage :size="16" class="shrink-0" />
          <span class="truncate">素材</span>
        </RouterLink>
        <RouterLink to="/files?focus=results" class="nav-link" active-class="active" exact>
          <IconImage :size="16" class="shrink-0" />
          <span class="truncate">产物</span>
        </RouterLink>
        <RouterLink to="/rubrics" class="nav-link" active-class="active" exact>
          <IconCheck :size="16" class="shrink-0" />
          <span class="truncate">Rubric评估</span>
        </RouterLink>
      </nav>

      <!-- 历史记录区域 -->
      <div class="history-section flex-1 overflow-y-auto mt-2 pt-2 border-t border-border">
        <HistoryList />
      </div>

      <!-- 底部用户菜单 -->
      <div class="user-footer p-3 border-t border-border mt-auto">
        <AppDropdownMenu>
          <template #trigger>
            <button class="user-trigger flex items-center gap-2 w-full px-2 py-2 rounded-md hover:bg-bg-sunken transition-colors">
              <AppAvatar :text="userInitial" :size="30" />
              <span class="flex-1 text-left text-sm font-medium text-fg-primary truncate">{{ displayName }}</span>
              <IconMoreHorizontal :size="16" class="text-fg-muted" />
            </button>
          </template>
          <AppDropdownMenuItem @select="$router.push('/settings')">
            设置
          </AppDropdownMenuItem>
          <AppDropdownMenuSeparator />
          <AppDropdownMenuItem danger @select="handleLogout">
            退出登录
          </AppDropdownMenuItem>
        </AppDropdownMenu>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.left-panel {
  background: var(--bg-page);
  border-right: 1px solid var(--border-strong);
  width: 300px;
  flex-shrink: 0;
  height: 100%;
  position: relative;
  transition: width 0.15s ease;
  z-index: 10;
}
.left-panel.collapsed {
  width: 12px;
}

/* 折叠态把手 */
.rail {
  width: 12px;
  height: 100%;
  background: var(--bg-sunken);
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 12px;
}
.rail-pin-btn {
  position: absolute;
  left: -12px;
  top: 12px;
  width: 30px;
  height: 30px;
  border-radius: 4px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  color: var(--fg-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s;
}
.rail:hover .rail-pin-btn {
  opacity: 1;
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
  transition: background-color 0.15s, color 0.15s;
}
.nav-link:hover {
  background-color: var(--bg-sunken);
  color: var(--fg-primary);
}
.nav-link.active {
  background-color: var(--accent-soft);
  color: var(--fg-primary);
  font-weight: 500;
}

.pin-btn {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  background: transparent;
  border: none;
  color: var(--fg-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.pin-btn:hover {
  background: var(--bg-sunken);
  color: var(--fg-primary);
}

.new-chat-btn {
  font-weight: 500;
  font-size: 14px;
}
</style>
