<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  deltas: string
  /** 用户消息列表（已在 transcript 里）。 */
  userMessages?: Array<{ id: string; text: string }>
}>()

const text = computed(() => props.deltas)
const local = ref('')
watch(() => props.deltas, (v) => {
  // 流式渲染：直接同步即可（deltas 是 ref 值）
  local.value = v
})
</script>

<template>
  <div class="message-stream">
    <div v-for="m in userMessages ?? []" :key="m.id" class="msg user">
      <div class="role">USER</div>
      <div class="content">{{ m.text }}</div>
    </div>
    <div class="msg assistant">
      <div class="role">ASSISTANT</div>
      <div class="content">{{ local }}</div>
    </div>
  </div>
</template>

<style scoped>
.message-stream {
  padding: 16px;
  font-size: 14px;
  line-height: 1.6;
}
.msg { margin-bottom: 16px; }
.role { font-size: 11px; color: var(--color-text-mute); margin-bottom: 4px; letter-spacing: 0.5px; }
.user .content { background: var(--color-bg-mute); padding: 8px 12px; border-radius: 6px; }
.assistant .content { padding: 8px 12px; white-space: pre-wrap; }
</style>