<script setup lang="ts">
import { computed } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useActivitiesStore } from '@/stores/activities'
import IconPin from '@/components/icons/IconPin.vue'
import IconPinOff from '@/components/icons/IconPinOff.vue'
import IconChevronLeft from '@/components/icons/IconChevronLeft.vue'
import IconX from '@/components/icons/IconX.vue'
import ActivityPanel from '@/components/activity/ActivityPanel.vue'

/**
 * RightPanel — 右栏全局 Activity 面板（frontend/tasks.md / product.md）
 *
 * 铁律：面板默认收起，新活动永不自动展开；展开只由用户显式点击驱动。
 *
 * 收起态：40px 导轨，垂直堆叠「进行中 / 已成功 / 已失败」计数徽章
 * 展开态：360px 面板（钉住转常驻）
 */

const ui = useUiStore()
const activities = useActivitiesStore()

const counts = computed(() => {
  let inProgress = 0
  let succeeded = 0
  let failed = 0
  for (const activity of activities.items.values()) {
    if (['UPLOADING', 'EXTRACTING', 'QUEUED', 'RUNNING'].includes(activity.status)) inProgress++
    else if (activity.status === 'SUCCEEDED') succeeded++
    else if (activity.status === 'FAILED' || activity.status === 'PARTIALLY_SUCCEEDED') failed++
  }
  return { inProgress, succeeded, failed }
})

const totalCount = computed(() => counts.value.inProgress + counts.value.succeeded + counts.value.failed)

const railTitle = computed(() => {
  const parts: string[] = []
  if (counts.value.inProgress > 0) parts.push(`进行中 ${counts.value.inProgress}`)
  if (counts.value.succeeded > 0) parts.push(`已成功 ${counts.value.succeeded}`)
  if (counts.value.failed > 0) parts.push(`已失败 ${counts.value.failed}`)
  return parts.length > 0 ? `活动：${parts.join(' · ')}` : '活动'
})

const expanded = computed(() => ui.rightPanelPinned || ui.rightPanelExpanded)
</script>

<template>
  <aside
    class="right-panel flex flex-col"
    :class="{ collapsed: !expanded }"
  >
    <!-- 收起态：40px 计数导轨，点击展开 -->
    <button
      v-if="!expanded"
      type="button"
      class="rail"
      :aria-label="`展开活动面板，${railTitle}`"
      :title="railTitle"
      @click="ui.setRightPanelExpanded(true)"
    >
      <span class="rail-expand">
        <IconChevronLeft :size="15" />
      </span>
      <span class="rail-badges">
        <span
          v-if="counts.inProgress > 0"
          class="rail-count tone-info"
        >{{ counts.inProgress }}</span>
        <span
          v-if="counts.succeeded > 0"
          class="rail-count tone-success"
        >{{ counts.succeeded }}</span>
        <span
          v-if="counts.failed > 0"
          class="rail-count tone-danger"
        >{{ counts.failed }}</span>
      </span>
    </button>

    <!-- 展开态：360px 面板 -->
    <div
      v-else
      class="panel-body flex h-full w-[360px] flex-col"
    >
      <header class="panel-header">
        <h2 class="panel-title">
          活动<span
            v-if="totalCount > 0"
            class="panel-count"
          >{{ totalCount }}</span>
        </h2>
        <div class="panel-actions">
          <button
            type="button"
            class="icon-btn"
            :aria-label="ui.rightPanelPinned ? '取消钉住' : '钉住面板'"
            :title="ui.rightPanelPinned ? '取消钉住' : '钉住面板'"
            @click="ui.toggleRightPanelPin()"
          >
            <IconPinOff
              v-if="ui.rightPanelPinned"
              :size="14"
            />
            <IconPin
              v-else
              :size="14"
            />
          </button>
          <button
            type="button"
            class="icon-btn"
            aria-label="收起活动面板"
            title="收起活动面板"
            @click="ui.setRightPanelExpanded(false)"
          >
            <IconX :size="14" />
          </button>
        </div>
      </header>
      <div class="panel-content">
        <ActivityPanel />
      </div>
    </div>
  </aside>
</template>

<style scoped>
.right-panel {
  background: var(--bg-page);
  border-left: 1px solid var(--border);
  width: 360px;
  flex-shrink: 0;
  height: 100%;
}
.right-panel.collapsed {
  width: 40px;
  border-left: none;
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
  width: 40px;
  height: 100%;
  background: var(--bg-sunken);
  border: none;
  border-left: 1px solid var(--border);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 0;
  gap: 12px;
  transition: background-color var(--dur-fast) var(--ease-out);
}
.rail:hover {
  background: var(--border);
}
.rail-expand {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: var(--fg-muted);
}
.rail-badges {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.rail-count {
  min-width: 20px;
  height: 20px;
  padding: 0 5px;
  border-radius: 9999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
}
.rail-count.tone-info {
  background: var(--info-soft);
  color: var(--info);
}
.rail-count.tone-success {
  background: var(--success-soft);
  color: var(--success);
}
.rail-count.tone-danger {
  background: var(--danger-soft);
  color: var(--danger);
}

/* 展开态 */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-primary);
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.panel-count {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-muted);
}
.panel-actions {
  display: flex;
  gap: 4px;
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
  background: var(--bg-sunken);
  color: var(--fg-primary);
}
.panel-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
</style>
