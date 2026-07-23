<script setup lang="ts">
import AppBadge from '@/components/ui/AppBadge.vue'

/**
 * ChatBubble — 单条消息气泡（frontend/chat.md / ui-visual.md）
 *
 * 安静原则：不放角色标签与头像——对齐方向已表达角色。
 * - 用户：右对齐，accent-soft 底
 * - 助手：左对齐，panel 白底 + 静置阴影
 * - 流式中：末尾呼吸光标；中断：「已中断」标记
 */
withDefaults(
  defineProps<{
    role: 'user' | 'assistant'
    text: string
    status?: 'streaming' | 'completed' | 'interrupted'
    timestamp?: string
  }>(),
  {
    status: 'completed',
    timestamp: undefined,
  }
)
</script>

<template>
  <div
    class="bubble-row"
    :class="role === 'user' ? 'justify-end' : 'justify-start'"
  >
    <div
      class="bubble-group"
      :class="role === 'user' ? 'items-end' : 'items-start'"
    >
      <div
        class="bubble-content"
        :class="role === 'user' ? 'bubble-user' : 'bubble-assistant'"
      >
        <span class="bubble-text">{{ text }}</span>
        <span
          v-if="status === 'streaming'"
          class="stream-caret"
          aria-hidden="true"
        />
      </div>
      <div
        v-if="status === 'interrupted' || timestamp"
        class="bubble-meta"
      >
        <AppBadge
          v-if="status === 'interrupted'"
          tone="warning"
          style="soft"
        >
          已中断
        </AppBadge>
        <span
          v-if="timestamp"
          class="timestamp"
        >{{ timestamp }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bubble-row {
  display: flex;
  width: 100%;
}
.bubble-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 82%;
}
.bubble-content {
  border-radius: 14px;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--fg-primary);
  overflow-wrap: break-word;
}
.bubble-user {
  background: var(--accent-soft);
}
.bubble-assistant {
  background: var(--bg-panel);
  box-shadow: var(--shadow-sm);
}
.bubble-text {
  white-space: pre-wrap;
}
.stream-caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -0.15em;
  background: var(--fg-secondary);
  animation: caret-blink 1s var(--ease-out) infinite;
}
@keyframes caret-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .stream-caret {
    animation: none;
    opacity: 1;
  }
}
.bubble-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 2px;
}
.timestamp {
  font-size: 12px;
  color: var(--fg-muted);
}
</style>
