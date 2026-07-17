<script setup lang="ts">
import AppAvatar from '@/components/ui/AppAvatar.vue'
import IconUser from '@/components/icons/IconUser.vue'

/**
 * ChatBubble — 单条消息气泡（web.md §7.3）
 *
 * 视觉：
 * - 用户气泡：右对齐 bg-accent-soft
 * - 助手气泡：左对齐 bg-bg-panel + shadow-sm
 * - 头部：可选 AppAvatar + 角色名 + 时间戳
 */
withDefaults(
  defineProps<{
    role: 'user' | 'assistant'
    text: string
    timestamp?: string
  }>(),
  {
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
      <div class="bubble-meta">
        <AppAvatar
          v-if="role === 'assistant'"
          :size="20"
          text="A"
        />
        <IconUser
          v-else
          :size="14"
          class="text-fg-muted"
        />
        <span class="role-label">{{ role === 'user' ? 'USER' : 'ASSISTANT' }}</span>
        <span
          v-if="timestamp"
          class="timestamp"
        >{{ timestamp }}</span>
      </div>
      <div
        :class="[
          'bubble-content rounded-lg px-4 py-3 max-w-[80%] break-words',
          role === 'user' ? 'bg-accent-soft text-fg-primary' : 'bg-bg-panel text-fg-primary shadow-sm'
        ]"
      >
        {{ text }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.bubble-row { display: flex; width: 100%; margin-bottom: 4px; }
.bubble-group { display: flex; flex-direction: column; max-width: 80%; }
.bubble-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
  font-size: 11px;
}
.role-label {
  color: var(--fg-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.timestamp { color: var(--fg-muted); }
.bubble-content { white-space: pre-wrap; }
</style>
