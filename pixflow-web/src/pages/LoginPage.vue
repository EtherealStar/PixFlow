<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppCard from '@/components/ui/AppCard.vue'
import AppInput from '@/components/ui/AppInput.vue'
import AppButton from '@/components/ui/AppButton.vue'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

/**
 * 登录页（/login）。
 * R6 stub：任意非空账密即可登录（R6 后期接 /api/auth）。
 */
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const toast = useToastStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)

async function onSubmit(): Promise<void> {
  if (!username.value.trim() || !password.value.trim()) return
  submitting.value = true
  try {
    await auth.login({ username: username.value.trim(), password: password.value.trim() })
    toast.push({ variant: 'success', message: `欢迎回来，${username.value}` })
    const redirect = (route.query.redirect as string | undefined) ?? '/'
    await router.replace(redirect)
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    toast.push({ variant: 'danger', message: msg })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page flex items-center justify-center min-h-screen bg-bg-page px-4">
    <AppCard padding="lg" class="w-full max-w-md">
      <header class="mb-6 text-center">
        <h1 class="text-xl font-semibold text-fg-primary mb-1">登录 PixFlow</h1>
        <p class="text-sm text-fg-secondary">使用您的账号继续</p>
      </header>

      <form class="flex flex-col gap-3" @submit.prevent="onSubmit">
        <AppInput v-model="username" placeholder="账号" autocomplete="username" />
        <AppInput
          v-model="password"
          type="password"
          placeholder="密码"
          autocomplete="current-password"
        />
        <AppButton
          variant="primary"
          type="submit"
          :loading="submitting"
          :disabled="!username.trim() || !password.trim()"
        >
          登录
        </AppButton>
        <p class="text-xs text-fg-muted text-center mt-2">
          当前为 R6 stub：任意非空账密均可登录
        </p>
      </form>
    </AppCard>
  </div>
</template>