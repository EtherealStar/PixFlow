<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppButton from '@/components/ui/AppButton.vue'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { authErrorMessage } from '@/utils/authErrors'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const toast = useToastStore()

const submitting = ref(false)
const pageError = ref('')
const loginForm = ref({ username: '', password: '' })
const fieldErrors = ref<Record<string, string>>({})
const canSubmit = computed(() =>
  !submitting.value && !!loginForm.value.username.trim() && !!loginForm.value.password
)

function clearErrors(): void {
  pageError.value = ''
  fieldErrors.value = {}
}

async function onSubmit(): Promise<void> {
  clearErrors()
  const username = loginForm.value.username.trim().toLowerCase()
  const password = loginForm.value.password
  const nextErrors: Record<string, string> = {}
  if (!username) nextErrors.loginUsername = '请输入用户名'
  if (!password) nextErrors.loginPassword = '请输入密码'
  if (Object.keys(nextErrors).length > 0) {
    fieldErrors.value = nextErrors
    return
  }

  submitting.value = true
  try {
    // 前端仅负责输入体验，Configured Administrator 资格始终由后端逐请求确认。
    await auth.login({ username, password })
    toast.push({ variant: 'success', message: `欢迎回来，${username}` })
    await router.replace(readRedirect())
  } catch (error) {
    pageError.value = authErrorMessage(error)
    toast.push({ variant: 'danger', message: pageError.value })
  } finally {
    submitting.value = false
  }
}

function readRedirect(): string {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/'
}
</script>

<template>
  <div class="login-page flex min-h-screen items-center justify-center bg-bg-page px-4 py-10">
    <AppCard
      padding="lg"
      class="w-full max-w-md"
    >
      <header class="mb-6 text-center">
        <h1 class="mb-1 text-xl font-semibold text-fg-primary">
          登录 PixFlow
        </h1>
        <p class="text-sm text-fg-secondary">
          使用管理员账号继续
        </p>
      </header>

      <p
        v-if="pageError"
        class="mb-4 rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger"
      >
        {{ pageError }}
      </p>

      <form
        class="flex flex-col gap-3"
        @submit.prevent="onSubmit"
      >
        <AppInput
          v-model="loginForm.username"
          name="username"
          autocomplete="username"
          placeholder="用户名"
          :error="fieldErrors.loginUsername"
        />
        <AppInput
          v-model="loginForm.password"
          name="current-password"
          type="password"
          autocomplete="current-password"
          placeholder="密码"
          :error="fieldErrors.loginPassword"
        />
        <AppButton
          variant="primary"
          type="submit"
          :loading="submitting"
          :disabled="!canSubmit"
        >
          登录 PixFlow
        </AppButton>
      </form>

      <p class="mt-4 text-center text-sm text-fg-secondary">
        暂未开放注册
      </p>
    </AppCard>
  </div>
</template>
