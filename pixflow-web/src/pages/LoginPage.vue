<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppButton from '@/components/ui/AppButton.vue'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { authErrorMessage } from '@/utils/authErrors'

type AuthMode = 'register' | 'login'

const USERNAME_PATTERN = /^[a-z0-9_]{3,32}$/

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const toast = useToastStore()

const mode = ref<AuthMode>(readMode())
const submitting = ref(false)
const pageError = ref('')

const registerForm = ref({
  username: '',
  displayName: '',
  password: '',
  confirmPassword: ''
})
const loginForm = ref({
  username: '',
  password: ''
})
const fieldErrors = ref<Record<string, string>>({})

const isRegisterMode = computed(() => mode.value === 'register')
const submitLabel = computed(() => (isRegisterMode.value ? '注册并进入 PixFlow' : '登录 PixFlow'))
const title = computed(() => (isRegisterMode.value ? 'PixFlow 账号注册' : '登录 PixFlow'))
const subtitle = computed(() =>
  isRegisterMode.value ? '创建新账号' : '使用已有账号继续'
)
const canSubmit = computed(() => {
  if (submitting.value) return false
  if (isRegisterMode.value) {
    return !!(
      registerForm.value.username.trim() &&
      registerForm.value.password &&
      registerForm.value.confirmPassword
    )
  }
  return !!(loginForm.value.username.trim() && loginForm.value.password)
})

watch(
  () => route.query.mode,
  () => {
    mode.value = readMode()
    clearErrors()
  }
)

function readMode(): AuthMode {
  return route.query.mode === 'login' ? 'login' : 'register'
}

function setMode(nextMode: AuthMode): void {
  if (mode.value === nextMode) return
  mode.value = nextMode
  clearErrors()
  router.replace({ query: { ...route.query, mode: nextMode } })
}

function normalizeUsername(value: string): string {
  return value.trim().toLowerCase()
}

function validateUsername(username: string): string | null {
  if (!USERNAME_PATTERN.test(username)) {
    return '用户名只能包含小写字母、数字和下划线，长度 3-32 位'
  }
  return null
}

function validatePassword(password: string): string | null {
  if (password.length < 8 || password.length > 128) {
    return '密码长度需为 8-128 位'
  }
  return null
}

function clearErrors(): void {
  pageError.value = ''
  fieldErrors.value = {}
}

async function onSubmit(): Promise<void> {
  clearErrors()
  if (isRegisterMode.value) {
    await submitRegister()
    return
  }
  await submitLogin()
}

async function submitRegister(): Promise<void> {
  const username = normalizeUsername(registerForm.value.username)
  const password = registerForm.value.password
  const displayName = registerForm.value.displayName.trim()
  const nextErrors: Record<string, string> = {}

  // 前端只做与后端一致的即时校验，安全边界仍以后端校验为准。
  const usernameError = validateUsername(username)
  if (usernameError) nextErrors.registerUsername = usernameError
  const passwordError = validatePassword(password)
  if (passwordError) nextErrors.registerPassword = passwordError
  if (registerForm.value.confirmPassword !== password) {
    nextErrors.confirmPassword = '两次输入的密码不一致'
  }

  if (Object.keys(nextErrors).length > 0) {
    fieldErrors.value = nextErrors
    return
  }

  submitting.value = true
  try {
    await auth.register({
      username,
      password,
      ...(displayName ? { displayName } : {})
    })
    toast.push({ variant: 'success', message: `欢迎加入，${displayName || username}` })
    await router.replace(readRedirect())
  } catch (error) {
    pageError.value = authErrorMessage(error)
    toast.push({ variant: 'danger', message: pageError.value })
  } finally {
    submitting.value = false
  }
}

async function submitLogin(): Promise<void> {
  const username = normalizeUsername(loginForm.value.username)
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
  if (typeof redirect === 'string' && redirect.startsWith('/')) return redirect
  return '/'
}
</script>

<template>
  <div class="login-page flex min-h-screen items-center justify-center bg-bg-page px-4 py-10">
    <AppCard padding="lg" class="w-full max-w-md">
      <header class="mb-6 text-center">
        <h1 class="mb-1 text-xl font-semibold text-fg-primary">{{ title }}</h1>
        <p class="text-sm text-fg-secondary">{{ subtitle }}</p>
      </header>

      <div class="mb-5 grid grid-cols-2 gap-2 rounded-md bg-bg-sunken p-1">
        <button
          type="button"
          :class="[
            'h-9 rounded-md text-sm font-medium transition-colors',
            isRegisterMode ? 'bg-bg-panel text-fg-primary shadow-sm' : 'text-fg-secondary hover:text-fg-primary'
          ]"
          @click="setMode('register')"
        >
          注册
        </button>
        <button
          type="button"
          :class="[
            'h-9 rounded-md text-sm font-medium transition-colors',
            !isRegisterMode ? 'bg-bg-panel text-fg-primary shadow-sm' : 'text-fg-secondary hover:text-fg-primary'
          ]"
          @click="setMode('login')"
        >
          登录
        </button>
      </div>

      <p v-if="pageError" class="mb-4 rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
        {{ pageError }}
      </p>

      <form v-if="isRegisterMode" class="flex flex-col gap-3" @submit.prevent="onSubmit">
        <AppInput
          v-model="registerForm.username"
          name="username"
          autocomplete="username"
          placeholder="用户名"
          :error="fieldErrors.registerUsername"
        />
        <AppInput
          v-model="registerForm.displayName"
          name="displayName"
          autocomplete="nickname"
          placeholder="展示名（可选）"
        />
        <AppInput
          v-model="registerForm.password"
          name="new-password"
          type="password"
          autocomplete="new-password"
          placeholder="密码"
          :error="fieldErrors.registerPassword"
        />
        <AppInput
          v-model="registerForm.confirmPassword"
          name="confirm-password"
          type="password"
          autocomplete="new-password"
          placeholder="确认密码"
          :error="fieldErrors.confirmPassword"
        />
        <AppButton variant="primary" type="submit" :loading="submitting" :disabled="!canSubmit">
          {{ submitLabel }}
        </AppButton>
      </form>

      <form v-else class="flex flex-col gap-3" @submit.prevent="onSubmit">
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
        <AppButton variant="primary" type="submit" :loading="submitting" :disabled="!canSubmit">
          {{ submitLabel }}
        </AppButton>
      </form>
    </AppCard>
  </div>
</template>
