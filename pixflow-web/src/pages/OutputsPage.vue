<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { deleteGeneratedImage, listGeneratedImages, listOutputConversations, listOutputTasks, renameGeneratedImage, type GeneratedImage, type OutputConversation, type OutputTask } from '@/api/outputs'
import AppButton from '@/components/ui/AppButton.vue'
import AppEmptyState from '@/components/ui/AppEmptyState.vue'
import ImageGrid from '@/components/files/ImageGrid.vue'
import IconImage from '@/components/icons/IconImage.vue'
import type { GalleryImageItem } from '@/types/files'

const conversations = ref<OutputConversation[]>([])
const tasks = ref<OutputTask[]>([])
const images = ref<GeneratedImage[]>([])
const selectedConversationId = ref<string | null>(null)
const selectedTaskId = ref<string | null>(null)
const loading = ref(false)

async function loadConversations(): Promise<void> {
  loading.value = true
  try {
    conversations.value = (await listOutputConversations({ page: 1, size: 20 })).records
  } finally {
    loading.value = false
  }
}

async function selectConversation(conversationId: string): Promise<void> {
  selectedConversationId.value = conversationId
  selectedTaskId.value = null
  images.value = []
  tasks.value = (await listOutputTasks(conversationId)).records
}

async function selectTask(taskId: string): Promise<void> {
  selectedTaskId.value = taskId
  images.value = (await listGeneratedImages(taskId)).records
}

function gallery(image: GeneratedImage): GalleryImageItem {
  return { id: image.imageId, src: image.previewUrl ?? '', filename: image.displayName, failed: !image.previewUrl }
}

function open(image: GalleryImageItem): void {
  if (image.src) window.open(image.src, '_blank', 'noopener,noreferrer')
}

async function rename(image: GeneratedImage): Promise<void> {
  const displayName = window.prompt('重命名产物', image.displayName)?.trim()
  if (!displayName || displayName === image.displayName) return
  const updated = await renameGeneratedImage(image.imageId, displayName)
  const current = images.value.find((candidate) => candidate.imageId === image.imageId)
  if (current) current.displayName = updated.displayName
}

async function remove(image: GeneratedImage): Promise<void> {
  if (!window.confirm(`删除“${image.displayName}”？`)) return
  await deleteGeneratedImage(image.imageId)
  images.value = images.value.filter((candidate) => candidate.imageId !== image.imageId)
}

onMounted(() => { void loadConversations() })
</script>

<template>
  <section class="flex h-full min-h-0 flex-col bg-bg-page">
    <header class="border-b border-border bg-bg-panel px-5 py-3">
      <h1 class="text-base font-semibold text-fg-primary">
        产物
      </h1>
    </header>
    <div class="grid min-h-0 flex-1 grid-cols-[240px_260px_minmax(0,1fr)] max-md:grid-cols-[200px_minmax(0,1fr)]">
      <nav class="overflow-y-auto border-r border-border p-2">
        <AppButton
          v-for="conversation in conversations"
          :key="conversation.conversationId"
          variant="ghost"
          class="mb-1 w-full justify-start"
          @click="selectConversation(conversation.conversationId)"
        >
          <span class="truncate">{{ conversation.title }}</span>
          <span class="ml-auto text-xs text-fg-muted">{{ conversation.generatedImageCount }}</span>
        </AppButton>
      </nav>
      <nav class="overflow-y-auto border-r border-border p-2 max-md:hidden">
        <AppButton
          v-for="task in tasks"
          :key="task.taskId"
          variant="ghost"
          class="mb-1 w-full justify-start"
          @click="selectTask(task.taskId)"
        >
          {{ task.taskType === 'IMAGEGEN' ? '图片生成' : '图片处理' }}
          <span class="ml-auto text-xs text-fg-muted">{{ task.generatedImageCount }}</span>
        </AppButton>
      </nav>
      <div class="overflow-y-auto">
        <div
          v-if="loading"
          class="p-8 text-center text-sm text-fg-muted"
        >
          正在加载产物...
        </div>
        <template v-else-if="images.length > 0">
          <ImageGrid
            :items="images.map(gallery)"
            :selected-ids="[]"
            @preview="open"
            @download="open"
            @open-external="open"
          />
          <div class="flex flex-wrap gap-2 border-t border-border p-3">
            <AppButton
              v-for="image in images"
              :key="image.imageId"
              variant="ghost"
              @click="rename(image)"
            >
              重命名 {{ image.displayName }}
            </AppButton>
            <AppButton
              v-for="image in images"
              :key="`delete-${image.imageId}`"
              variant="ghost"
              @click="remove(image)"
            >
              删除 {{ image.displayName }}
            </AppButton>
          </div>
        </template>
        <AppEmptyState
          v-else
          :icon="IconImage"
          title="暂无产物"
          description="选择会话和任务以浏览生成图片"
        />
      </div>
    </div>
  </section>
</template>
