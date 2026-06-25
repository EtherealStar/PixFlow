<script setup>
import { ref, reactive, onMounted, nextTick, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { conversationApi, assetApi } from '../api'
import { formatTime } from '../utils/format'

/* ---- 对话列表 ---- */
const conversations = ref([])
const activeId = ref(null)

async function loadConversations() {
  const res = await conversationApi.list({ page: 1, size: 100 })
  conversations.value = res.records || []
  if (!activeId.value && conversations.value.length) {
    selectConversation(conversations.value[0].id)
  }
}

async function newConversation() {
  const res = await conversationApi.create()
  await loadConversations()
  selectConversation(res.conversationId)
}

/* ---- 消息历史 ---- */
const messages = ref([])
const msgLoading = ref(false)
const threadRef = ref(null)

async function selectConversation(id) {
  activeId.value = id
  await loadMessages()
}

async function loadMessages() {
  if (!activeId.value) return
  msgLoading.value = true
  try {
    messages.value = await conversationApi.messages(activeId.value)
    scrollToBottom()
  } finally {
    msgLoading.value = false
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (threadRef.value) threadRef.value.scrollTop = threadRef.value.scrollHeight
  })
}

/* ---- 素材包选择 ---- */
const packages = ref([])
const selectedPackage = ref(null)

async function loadReadyPackages() {
  const res = await assetApi.list({ page: 1, size: 100, sortBy: 'created_at', order: 'desc' })
  // 仅就绪（status=1）素材包可参与处理
  packages.value = (res.records || []).filter((p) => p.status === 1)
}

/* ---- 发送 ---- */
const input = ref('')
const sending = ref(false)
const parse = reactive({ needConfirm: false, missingParams: [], dagPreview: null, reply: '' })

function resetParse() {
  parse.needConfirm = false
  parse.missingParams = []
  parse.dagPreview = null
  parse.reply = ''
}

async function send() {
  if (!activeId.value) {
    ElMessage.warning('请先选择或新建对话')
    return
  }
  const content = input.value.trim()
  if (!content) {
    ElMessage.warning('请输入指令内容')
    return
  }
  sending.value = true
  try {
    const body = { content, attachedPackageId: selectedPackage.value || null }
    const res = await conversationApi.send(activeId.value, body)
    input.value = ''
    resetParse()
    parse.needConfirm = res.needConfirm
    parse.missingParams = res.missingParams || []
    parse.dagPreview = res.dagPreview || null
    parse.reply = res.reply || ''
    await loadMessages()
    await loadConversations() // 刷新标题
  } finally {
    sending.value = false
  }
}

/* ---- 确认执行 ---- */
const confirming = ref(false)
const confirmResult = ref(null)
const canConfirm = computed(() => parse.dagPreview && selectedPackage.value)

async function confirm() {
  if (!canConfirm.value) {
    ElMessage.warning('需要有效的 DAG 预览并选中素材包')
    return
  }
  confirming.value = true
  confirmResult.value = null
  try {
    const body = {
      dagJson: JSON.stringify(parse.dagPreview),
      packageId: selectedPackage.value
    }
    const res = await conversationApi.confirm(activeId.value, body)
    confirmResult.value = res
    ElMessage.success(res.status === 2 ? '任务执行完成' : '任务执行结束（含失败）')
    resetParse()
    await loadMessages()
  } finally {
    confirming.value = false
  }
}

onMounted(() => {
  loadConversations()
  loadReadyPackages()
})
</script>

<template>
  <div class="chat-layout">
    <!-- 对话列表 -->
    <el-card class="conv-list" shadow="never" :body-style="{ padding: '0' }">
      <div class="conv-head">
        <span>对话</span>
        <el-button size="small" type="primary" :icon="'Plus'" @click="newConversation">新建</el-button>
      </div>
      <el-scrollbar height="calc(100vh - 200px)">
        <div
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: c.id === activeId }"
          @click="selectConversation(c.id)"
        >
          <div class="conv-title">{{ c.title || '（新对话）' }}</div>
          <div class="conv-time">{{ formatTime(c.updatedAt) }}</div>
        </div>
        <el-empty v-if="conversations.length === 0" description="暂无对话" :image-size="60" />
      </el-scrollbar>
    </el-card>

    <!-- 对话主区 -->
    <el-card class="conv-main" shadow="never" :body-style="{ padding: '0', height: '100%' }">
      <div v-if="!activeId" class="empty-main">
        <el-empty description="选择或新建一个对话开始" />
      </div>
      <div v-else class="main-inner">
        <div class="msg-thread" ref="threadRef" v-loading="msgLoading">
          <div v-for="m in messages" :key="m.id" class="msg" :class="m.role">
            <div class="bubble">
              <div class="text">{{ m.content }}</div>
              <div class="msg-meta">
                <span v-if="m.attachedPackageId">📎 包 #{{ m.attachedPackageId }}</span>
                <span v-if="m.taskId">✓ 任务 #{{ m.taskId }}</span>
                <span>{{ formatTime(m.createdAt) }}</span>
              </div>
            </div>
          </div>
          <el-empty v-if="!msgLoading && messages.length === 0" description="发送第一条指令" :image-size="60" />
        </div>

        <!-- 解析结果区 -->
        <div v-if="parse.reply || parse.missingParams.length || parse.dagPreview" class="parse-panel">
          <el-alert v-if="parse.reply" :title="parse.reply" type="info" :closable="false" show-icon />

          <div v-if="parse.missingParams.length" class="missing">
            <el-tag type="warning">需补充参数</el-tag>
            <ul>
              <li v-for="(mp, i) in parse.missingParams" :key="i">
                节点 <code>{{ mp.nodeId }}</code> 缺少参数 <code>{{ mp.param }}</code>
              </li>
            </ul>
          </div>

          <div v-if="parse.dagPreview" class="dag-preview">
            <div class="dag-head">
              <el-tag type="success">DAG 预览</el-tag>
              <span>{{ (parse.dagPreview.nodes || []).length }} 个节点 / {{ (parse.dagPreview.edges || []).length }} 条边</span>
            </div>
            <div class="nodes">
              <el-tag v-for="n in parse.dagPreview.nodes" :key="n.id" class="node-tag" effect="plain">
                {{ n.id }}: {{ n.tool }}
              </el-tag>
            </div>
            <el-button
              type="primary"
              :icon="'VideoPlay'"
              :loading="confirming"
              :disabled="!canConfirm"
              @click="confirm"
            >
              确认执行
            </el-button>
            <span v-if="!selectedPackage" class="hint">请先在下方选择素材包</span>
          </div>
        </div>

        <!-- 执行结果区 -->
        <el-alert
          v-if="confirmResult"
          class="confirm-result"
          :type="confirmResult.status === 2 ? 'success' : 'error'"
          :closable="true"
          show-icon
          :title="`任务 #${confirmResult.taskId} — 完成 ${confirmResult.doneCount}/${confirmResult.totalCount}`"
        >
          <div class="preview-row">
            <el-image
              v-for="(url, i) in confirmResult.resultPreviewUrls"
              :key="i"
              :src="url"
              :preview-src-list="confirmResult.resultPreviewUrls"
              :initial-index="i"
              fit="contain"
              class="preview-thumb"
              preview-teleported
            />
          </div>
        </el-alert>

        <!-- 输入区 -->
        <div class="composer">
          <div class="composer-tools">
            <el-select v-model="selectedPackage" placeholder="选择素材包（就绪）" clearable size="default" style="width: 280px">
              <el-option
                v-for="p in packages"
                :key="p.id"
                :label="`#${p.id} ${p.name}（${p.imageCount} 张）`"
                :value="p.id"
              />
            </el-select>
            <el-button text :icon="'Refresh'" @click="loadReadyPackages">刷新素材包</el-button>
          </div>
          <div class="composer-input">
            <el-input
              v-model="input"
              type="textarea"
              :rows="2"
              maxlength="4000"
              show-word-limit
              placeholder="用自然语言描述处理意图，例如：去背景、白底、压到200KB、右下角加水印，并生成卖点文案"
              @keydown.enter.exact.prevent="send"
            />
            <el-button type="primary" :icon="'Promotion'" :loading="sending" @click="send">发送</el-button>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.chat-layout {
  display: flex;
  gap: 16px;
  height: calc(100vh - 100px);
}
.conv-list {
  width: 260px;
  flex-shrink: 0;
}
.conv-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border-bottom: 1px solid #ebeef5;
  font-weight: 600;
}
.conv-item {
  padding: 10px 14px;
  cursor: pointer;
  border-bottom: 1px solid #f5f7fa;
}
.conv-item:hover {
  background: #f5f7fa;
}
.conv-item.active {
  background: #ecf5ff;
}
.conv-title {
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-time {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 2px;
}
.conv-main {
  flex: 1;
  min-width: 0;
}
.empty-main {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.main-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.msg-thread {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}
.msg {
  display: flex;
  margin-bottom: 12px;
}
.msg.user {
  justify-content: flex-end;
}
.bubble {
  max-width: 70%;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f4f4f5;
}
.msg.user .bubble {
  background: #ecf5ff;
}
.bubble .text {
  white-space: pre-wrap;
  word-break: break-word;
  color: #303133;
}
.msg-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #909399;
  margin-top: 6px;
}
.parse-panel {
  border-top: 1px solid #ebeef5;
  padding: 12px 16px;
  background: #fafafa;
  max-height: 240px;
  overflow-y: auto;
}
.missing ul {
  margin: 8px 0 0;
  padding-left: 20px;
  font-size: 13px;
}
.dag-preview {
  margin-top: 10px;
}
.dag-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}
.nodes {
  margin: 8px 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.node-tag {
  font-family: monospace;
}
.hint {
  margin-left: 10px;
  color: #e6a23c;
  font-size: 13px;
}
.confirm-result {
  margin: 0 16px;
}
.preview-row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.preview-thumb {
  width: 80px;
  height: 80px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.composer {
  border-top: 1px solid #ebeef5;
  padding: 12px 16px;
}
.composer-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.composer-input {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.composer-input .el-textarea {
  flex: 1;
}
</style>
