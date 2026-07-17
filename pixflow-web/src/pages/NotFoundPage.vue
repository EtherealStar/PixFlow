<script setup lang="ts">
import { useRouter } from 'vue-router'
import AppCard from '@/components/ui/AppCard.vue'
import AppButton from '@/components/ui/AppButton.vue'
import IconAlertCircle from '@/components/icons/IconAlertCircle.vue'

/**
 * 404 页面（web.md §十一）
 */
const router = useRouter()
</script>

<template>
  <section class="not-found-page flex items-center justify-center min-h-screen bg-bg-page px-4">
    <AppCard
      padding="lg"
      class="w-full max-w-md text-center"
    >
      <IconAlertCircle
        :size="40"
        class="text-warning mx-auto mb-3"
      />
      <h1 class="text-xl font-semibold text-fg-primary mb-1">
        404 · 页面不存在
      </h1>
      <p class="text-sm text-fg-secondary mb-6">
        您访问的页面已被移动或不存在
      </p>
      <AppButton
        variant="primary"
        @click="router.push('/')"
      >
        回到首页
      </AppButton>
    </AppCard>
  </section>
</template>