<script setup lang="ts">
import { computed, ref } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useTasksStore } from '@/stores/tasks'
import { useAuthStore } from '@/stores/auth'
import IconPin from '@/components/icons/IconPin.vue'
import IconPinOff from '@/components/icons/IconPinOff.vue'
import IconX from '@/components/icons/IconX.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import TaskPanel from '@/components/tasks/TaskPanel.vue'

/**
 * RightPanel — 右侧任务面板（web.md §十）
 *
 * 状态机（与 useTask.TaskPhase 对齐，小写枚举）：
 * - 进行中：phase ∈ {queued, running}
 * - 已完成：phase = completed
 * - 失败：phase ∈ {failed, partial, cancelled}
 *
 * 收起态：8px 把手 + 3 枚垂直堆叠徽章
 * 展开态：360px 任务面板（TaskPanel）
 *
 * 触发规则（web.md §十 + auth 决策补充）：
 * - 默认收起；新任务产生时 autoExpand 6s 收回
 * - 钉住转常驻
 * - 未登录时不显示右栏
 */

const ui = useUiStore()
const tasks = useTasksStore()
const auth = useAuthStore()

// 状态统计
const counts = computed(() => {
  let inProgress = 0
  let completed = 0
  let failed = 0
  for (const t of tasks.items.values()) {
    if (t.phase === 'queued' || t.phase === 'running') inProgress++
    else if (t.phase === 'completed') completed++
    else if (t.phase === 'failed' || t.phase === 'partial' || t.phase === 'cancelled') failed++
  }
  return { inProgress, completed, failed }
})

const totalCount = computed(() => counts.value.inProgress + counts.value.completed + counts.value.failed)

const hovering = ref(false)
let hoverTimer: ReturnType<typeof setTimeout> | null = null

function onEnter(): void {
  if (ui.rightPanelPinned) return
  hovering.value = true
  if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null }
}
function onLeave(): void {
  if (ui.rightPanelPinned) return
  hoverTimer = setTimeout(() => { hovering.value = false; hoverTimer = null }, 1500)
}

const effectiveExpanded = computed(() => ui.rightPanelPinned || ui.rightPanelExpanded || hovering.value)
</script>

<template>
  <aside
    class="right-panel flex flex-col"
    :class="{ collapsed: !effectiveExpanded }"
    @mouseenter="onEnter"
    @mouseleave="onLeave"
  >
    <!-- 把手 -->
    <div v-if="!effectiveExpanded" class="rail" @click="ui.setRightPanelExpanded(true)">
      <div class="rail-bar"></div>
      <div class="badges">
        <AppBadge v-if="counts.inProgress > 0" tone="info" style="solid">
          {{ counts.inProgress }}
        </AppBadge>
        <AppBadge v-if="counts.completed > 0" tone="success" style="solid">
          {{ counts.completed }}
        </AppBadge>
        <AppBadge v-if="counts.failed > 0" tone="danger" style="solid">
          {{ counts.failed }}
        </AppBadge>
      </div>
    </div>

    <!-- 展开态 -->
    <div v-show="effectiveExpanded" class="panel-body flex flex-col h-full w-[360px]">
      <header class="panel-header">
        <h3 class="panel-title">任务 ({{ totalCount }})</h3>
        <div class="panel-actions">
          <button
            type="button"
            class="action-btn"
            :aria-label="ui.rightPanelPinned ? '取消钉住' : '钉住面板'"
            @click="ui.toggleRightPanelPin()"
          >
            <IconPin v-if="!ui.rightPanelPinned" :size="14" />
            <IconPinOff v-else :size="14" />
          </button>
          <button
            type="button"
            class="action-btn"
            aria-label="关闭"
            @click="ui.setRightPanelExpanded(false)"
          >
            <IconX :size="14" />
          </button>
        </div>
      </header>
      <div class="panel-content">
        <TaskPanel />
      </div>
    </div>
  </aside>
</template>

<style scoped>
.right-panel {
  background: var(--bg-page);
  border-left: 1px solid var(--border-strong);
  width: 360px;
  flex-shrink: 0;
  height: 100%;
  position: relative;
  transition: width 0.15s ease;
  z-index: 10;
}
.right-panel.collapsed {
  width: 12px;
}

/* 把手 */
.rail {
  width: 12px;
  height: 100%;
  background: var(--bg-sunken);
  position: absolute;
  top: 12px;
  right: -10px;
  width: 20px;
  height: 40px;
  border-radius: 4px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.5;
  transition: opacity 0.15s;
}
.rail:hover .rail-toggle { opacity: 1; }
.rail-bar {
  display: block;
  width: 2px;
  height: 16px;
  background: var(--fg-muted);
  border-radius: 1px;
}

/* 徽章（垂直堆叠） */
.badges {
  position: absolute;
  top: 60px;
  right: -14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
}

/* 展开态 */
.panel-body {
  width: 360px;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 12px;
  background: var(--bg-panel);
  border-bottom: 1px solid var(--border);
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-primary);
  margin: 0;
}
.panel-actions {
  display: flex;
  gap: 4px;
}
.action-btn {
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
}
.action-btn:hover {
  background: var(--bg-sunken);
  color: var(--fg-primary);
}
.panel-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
</style>