<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import AppCard from '@/components/ui/AppCard.vue'
import AppButton from '@/components/ui/AppButton.vue'
import IconChat from '@/components/icons/IconChat.vue'
import IconFolder from '@/components/icons/IconFolder.vue'
import IconImage from '@/components/icons/IconImage.vue'
import IconUpload from '@/components/icons/IconUpload.vue'
import Composer from '@/components/chat/Composer.vue'

/**
 * 主页（/）：R6 阶段接入，web.md §十一
 *
 * 提供 4 个入口：新建对话 / 我的素材 / 任务列表 / 上传。
 * 视觉走 token；颜色 / 圆角 / 间距全部走 design tokens。
 */
const router = useRouter()
const ui = useUiStore()

const prompt = ref('')

function onSend() {
  if (!prompt.value.trim()) return
  const q = encodeURIComponent(prompt.value)
  prompt.value = ''
  router.push(`/chat/new?q=${q}`)
}

const cards: Array<{
  icon: unknown
  title: string
  description: string
  cta: string
  to?: string
  action?: () => void
}> = [
  {
    icon: IconChat,
    title: '新建对话',
    description: '开始一次新的素材处理会话',
    cta: '开始',
    to: '/chat/new',
  },
  {
    icon: IconFolder,
    title: '我的素材',
    description: '查看已上传的压缩包和生成的产物',
    cta: '打开',
    to: '/files',
  },
  {
    icon: IconUpload,
    title: '上传素材',
    description: '把本地压缩包拖入上传队列',
    cta: '去上传',
    to: '/files?upload=1',
  },
  {
    icon: IconImage,
    title: '任务',
    description: '查看右栏任务面板的实时进度',
    cta: '查看',
    action: () => ui.setRightPanelExpanded(true),
  },
]
</script>

<template>
  <section class="home-page flex flex-col h-full relative">
    <div class="p-6 md:p-10 max-w-5xl mx-auto w-full flex-1 overflow-y-auto">
    <header class="mb-8">
      <h1 class="text-2xl font-semibold text-fg-primary mb-2">PixFlow · 专属于你的AI电商运营助手</h1>
      <p class="text-sm text-fg-secondary">精准解析自然语言指令，智能编排图片处理，沉淀高效经营洞察</p>
    </header>

    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <AppCard
        v-for="card in cards"
        :key="card.title"
        hoverable
        padding="lg"
        class="home-card flex flex-col gap-3"
      >
        <component :is="card.icon" :size="28" class="text-accent" />
        <div class="flex-1">
          <h2 class="text-base font-medium text-fg-primary mb-1">{{ card.title }}</h2>
          <p class="text-sm text-fg-secondary">{{ card.description }}</p>
        </div>
        <AppButton variant="primary" size="sm" @click="card.action ? card.action() : router.push(card.to!)">
          {{ card.cta }}
        </AppButton>
      </AppCard>
    </div>
    </div>

    <div class="w-full max-w-3xl mx-auto px-6 pb-6 mt-auto">
      <Composer v-model="prompt" @send="onSend" />
    </div>
  </section>
</template>