<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { ApiError } from '@/types/api'
import type { ConfirmationChallenge, Proposal } from '@/types/agent'

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

async function onSubmit(): Promise<void> {
  if (!answer.value.trim()) {
    ElMessage.warning('请输入答案')
    return
  }
  submitting.value = true
  try {
    emit('submit', answer.value.trim())
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    title="二次确认"
    width="480px"
    :close-on-click-modal="false"
  >
    <div v-if="challenge">
      <div class="prompt">{{ challenge.prompt }}</div>
      <el-input v-model="answer" type="textarea" :rows="3" placeholder="请输入答案..." />
      <div v-if="error" class="error">错误：{{ error.message }}</div>
    </div>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="onSubmit">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.prompt {
  margin-bottom: 12px;
  padding: 12px;
  background: var(--color-bg-soft);
  border-radius: 4px;
  font-size: 14px;
}
.error {
  margin-top: 8px;
  color: var(--color-error);
  font-size: 12px;
}
</style>