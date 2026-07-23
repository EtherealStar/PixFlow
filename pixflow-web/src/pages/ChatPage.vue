<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MessageStream from '@/components/chat/MessageStream.vue'
import ProposalCard from '@/components/chat/ProposalCard.vue'
import Composer from '@/components/chat/Composer.vue'
import QueuePanel from '@/components/chat/QueuePanel.vue'
import IconPackage from '@/components/icons/IconPackage.vue'
import { useChatSession } from '@/runtime/useChatSession'
import { useConversationsStore } from '@/stores/conversations'
import { useUploadJobsStore } from '@/stores/uploadJobs'
import { useToastStore } from '@/stores/toast'

/**
 * 会话页（/chat/:conversationId），见 frontend/chat.md。
 *
 * - 标题栏显示会话标题（不显示裸 ID）
 * - 消息列限宽 768px 居中；空态给可点击的起手示例
 * - 整页接受压缩包拖拽上传；进度只出现在右侧活动面板
 */
const route = useRoute()
const router = useRouter()
const chat = useChatSession({ route, router })
const conversations = useConversationsStore()
const uploadJobs = useUploadJobsStore()
const toast = useToastStore()

const title = computed(() => conversations.current?.title?.trim() || '新对话')

const isWorking = computed(() =>
  chat.currentPhase.value === 'sending' || chat.currentPhase.value === 'streaming')

const isEmpty = computed(() =>
  chat.userMessages.value.length === 0 &&
  chat.streamTimeline.value.length === 0 &&
  chat.visibleProposals.value.length === 0 &&
  chat.currentPhase.value === 'idle')

const waitingForProposal = computed(() =>
  chat.visibleProposals.value.some((p) => !p.confirmedTaskId))

/* 提案确认防重复点击（AgentTurnPhase 无 awaiting_confirm，本地跟踪） */
const confirmingId = ref<string | null>(null)
async function onConfirmProposal(p: { proposalId: string }): Promise<void> {
  if (confirmingId.value) return
  confirmingId.value = p.proposalId
  try {
    await chat.confirmProposal(p as never)
  } finally {
    confirmingId.value = null
  }
}

const examples = [
  '把这批商品图换成白底，再裁成 1:1',
  '为 @ 素材包生成 4 张场景图',
  '分析这个商品最近 30 天的销售趋势',
]

function fillExample(text: string): void {
  chat.composerText.value = text
}

/* 拖拽上传：整页投放区，仅压缩包（frontend/product.md §Materials and uploads） */
const ARCHIVE_PATTERN = /\.(zip|rar|7z)$/i
const dragDepth = ref(0)
const dragActive = computed(() => dragDepth.value > 0)

function onDragEnter(ev: DragEvent): void {
  if (!ev.dataTransfer?.types.includes('Files')) return
  ev.preventDefault()
  dragDepth.value += 1
}
function onDragOver(ev: DragEvent): void {
  if (!ev.dataTransfer?.types.includes('Files')) return
  ev.preventDefault()
}
function onDragLeave(ev: DragEvent): void {
  if (!ev.dataTransfer?.types.includes('Files')) return
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}
function onDrop(ev: DragEvent): void {
  if (!ev.dataTransfer?.types.includes('Files')) return
  ev.preventDefault()
  dragDepth.value = 0
  const files = ev.dataTransfer.files ? Array.from(ev.dataTransfer.files) : []
  if (files.length === 0) return
  const valid = files.filter((f) => ARCHIVE_PATTERN.test(f.name))
  if (valid.length < files.length) {
    toast.push({ variant: 'danger', message: '只能上传压缩文件' })
  }
  startUploads(valid)
}

function startUploads(files: File[]): void {
  for (const f of files) {
    void uploadJobs.startWholeFile(f).catch((err: unknown) => {
      const msg = err instanceof Error ? err.message : String(err)
      toast.push({ variant: 'danger', message: `上传失败:${msg}` })
    })
  }
  if (files.length > 0) {
    toast.push({ variant: 'info', message: '已开始上传，可在右侧活动面板查看进度' })
  }
}
</script>

<template>
  <section
    class="chat-page flex h-full flex-col bg-bg-page"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <header class="chat-head">
      <h1 class="chat-title">
        {{ title }}
      </h1>
      <span
        v-if="isWorking"
        class="chat-status"
        role="status"
      >
        <span class="status-dot" />正在生成…
      </span>
    </header>

    <div class="chat-body flex-1 overflow-y-auto">
      <!-- 空态：一屏至多一处的 Display 标题 + 可点击起手示例 -->
      <div
        v-if="isEmpty"
        class="welcome"
      >
        <h2 class="welcome-title">
          从一句指令开始
        </h2>
        <p class="welcome-desc">
          用自然语言描述批量图片任务，或把素材压缩包直接拖进来。
        </p>
        <div class="welcome-examples">
          <button
            v-for="example in examples"
            :key="example"
            type="button"
            class="example-chip"
            @click="fillExample(example)"
          >
            {{ example }}
          </button>
        </div>
      </div>

      <MessageStream
        :timeline="chat.streamTimeline.value"
        :user-messages="chat.userMessages.value"
      />

      <div class="proposal-list flex flex-col gap-3 px-6 pb-2">
        <ProposalCard
          v-for="p in chat.visibleProposals.value"
          :key="p.proposalId"
          :proposal="p"
          :busy="confirmingId === p.proposalId"
          @confirm="onConfirmProposal(p)"
          @reject="chat.rejectProposal(p)"
        />
      </div>
    </div>

    <footer class="chat-foot px-6 pb-4 pt-2">
      <QueuePanel
        :items="chat.queuedMessages.value"
        :paused="chat.queuePaused.value"
        :waiting-for-proposal="waitingForProposal"
        @begin-edit="chat.beginQueuedEdit"
        @save-edit="chat.saveQueuedEdit"
        @cancel-edit="chat.cancelQueuedEdit"
        @cancel="chat.cancelQueued"
        @continue="chat.continueQueue"
      />
      <Composer
        v-model="chat.composerText.value"
        v-model:references="chat.composerReferences.value"
        :sending="chat.sending.value"
        :streaming="isWorking"
        @send="chat.sendComposer"
        @stop="chat.stop"
        @upload="startUploads"
      />
    </footer>

    <!-- 拖拽遮罩 -->
    <div
      v-if="dragActive"
      class="drop-overlay"
      aria-hidden="true"
    >
      <div class="drop-box">
        <IconPackage
          :size="28"
          class="text-accent"
        />
        <p class="drop-title">
          松开以上传素材包
        </p>
        <p class="drop-desc">
          支持 .zip / .rar / .7z，进度在右侧活动面板查看
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.chat-page {
  position: relative;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 48px;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-panel);
  flex-shrink: 0;
}
.chat-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-primary);
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-muted);
  flex-shrink: 0;
}
.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: var(--info);
  animation: status-pulse 1.2s var(--ease-out) infinite;
}
@keyframes status-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* 空态欢迎区 */
.welcome {
  width: 100%;
  max-width: 768px;
  margin: 0 auto;
  padding: 96px 24px 48px;
}
.welcome-title {
  font-size: 30px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--fg-primary);
  margin: 0;
}
.welcome-desc {
  margin: 12px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--fg-secondary);
  max-width: 65ch;
}
.welcome-examples {
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.example-chip {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 8px 14px;
  font-size: 14px;
  color: var(--fg-secondary);
  cursor: pointer;
  text-align: left;
  box-shadow: var(--shadow-sm);
  transition: color var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out),
    box-shadow var(--dur-fast) var(--ease-out),
    transform var(--dur-fast) var(--ease-out);
}
.example-chip:hover {
  color: var(--fg-primary);
  border-color: var(--border-strong);
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}
.example-chip:active {
  transform: scale(0.98);
}

/* 拖拽遮罩 */
.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: var(--z-overlay);
  background: var(--accent-soft-scrim);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.drop-box {
  width: 100%;
  height: 100%;
  border: 2px dashed var(--accent);
  border-radius: 20px;
  background: var(--bg-panel);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
.drop-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--fg-primary);
  margin: 0;
}
.drop-desc {
  font-size: 13px;
  color: var(--fg-secondary);
  margin: 0;
}
</style>
