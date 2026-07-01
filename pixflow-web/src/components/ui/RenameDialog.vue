<script setup lang="ts">
import { ref, watch } from 'vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppButton from '@/components/ui/AppButton.vue'

/**
 * RenameDialog — 统一的重命名弹窗（web.md §七 / §二十一）
 *
 * 包装 AppDialog；输入框受控，提交校验非空 + 与初始名不同。
 */
const props = defineProps<{
  open: boolean
  initialName: string
  title?: string
  description?: string
  submitting?: boolean
}>()

const emit = defineEmits<{
  'update:open': [v: boolean]
  submit: [next: string]
}>()

const draft = ref('')

watch(
  () => props.open,
  (v) => {
    if (v) {
      draft.value = props.initialName
    }
  }
)

function onSubmit(): void {
  const v = draft.value.trim()
  if (!v || v === props.initialName) return
  emit('submit', v)
}
</script>

<template>
  <AppDialog
    :open="open"
    :title="title ?? '重命名'"
    :description="description ?? '请输入新的名称'"
    @update:open="(v: boolean) => $emit('update:open', v)"
  >
    <template #body>
      <AppInput v-model="draft" placeholder="新名称" @keydown.enter="onSubmit" />
    </template>
    <template #footer>
      <AppButton variant="secondary" @click="$emit('update:open', false)">取消</AppButton>
      <AppButton variant="primary" :loading="submitting" @click="onSubmit">确认</AppButton>
    </template>
  </AppDialog>
</template>
