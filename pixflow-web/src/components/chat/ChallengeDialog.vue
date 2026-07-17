<script setup lang="ts">
import { ref, watch } from 'vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import AppTextarea from '@/components/ui/AppTextarea.vue'
import AppButton from '@/components/ui/AppButton.vue'
import type { ApiError } from '@/types/api'
import type { ConfirmationChallenge, Proposal } from '@/types/agent'

/**
 * ChallengeDialog — 二次确认弹窗（web.md §7.3 / §二十一）
 *
 * 包装 AppDialog；内含 AppTextarea + 错误提示（text-danger）+ 提交/取消按钮。
 */
const props = defineProps<{
  visible: boolean
  proposal: Proposal | null
  challenge: ConfirmationChallenge | null
  error?: ApiError | null
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  submit: [answer: string]
}>()

const answer = ref('')
const submitting = ref(false)

watch(() => props.visible, (v) => {
  if (v) {
    answer.value = ''
    submitting.value = false
  }
})

function onSubmit(): void {
  if (!answer.value.trim()) return
  submitting.value = true
  try {
    emit('submit', answer.value.trim())
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AppDialog
    :open="visible"
    title="二次确认"
    @update:open="(v: boolean) => $emit('update:visible', v)"
  >
    <template #body>
      <div v-if="challenge">
        <div class="bg-bg-sunken rounded-md p-3 mb-3 text-sm text-fg-secondary">
          {{ challenge.prompt }}
        </div>
        <AppTextarea
          v-model="answer"
          :rows="3"
          placeholder="请输入答案..."
        />
        <p
          v-if="error"
          class="text-xs text-danger mt-2"
        >
          错误：{{ error.message }}
        </p>
      </div>
    </template>
    <template #footer>
      <AppButton
        variant="secondary"
        @click="$emit('update:visible', false)"
      >
        取消
      </AppButton>
      <AppButton
        variant="primary"
        :loading="submitting"
        @click="onSubmit"
      >
        提交
      </AppButton>
    </template>
  </AppDialog>
</template>
