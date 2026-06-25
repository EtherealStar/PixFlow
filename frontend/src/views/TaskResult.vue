<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { taskApi, resultApi } from '../api'
import { TASK_STATUS, RESULT_STATUS, formatTime } from '../utils/format'

/* ---- 任务列表 ---- */
const loading = ref(false)
const tasks = ref([])
const query = reactive({ page: 1, size: 10, status: null })
const total = ref(0)

const statusFilters = [
  { label: '全部', value: null },
  { label: '待执行', value: 0 },
  { label: '执行中', value: 1 },
  { label: '完成', value: 2 },
  { label: '失败', value: 3 }
]

async function loadTasks() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.status !== null) params.status = query.status
    const res = await taskApi.list(params)
    tasks.value = res.records || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  query.page = 1
  loadTasks()
}

/* ---- 任务详情 ---- */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const resultPage = reactive({ page: 1, size: 12 })
const resultTotalNote = ref('')

async function openDetail(row) {
  detailVisible.value = true
  resultPage.page = 1
  await loadDetail(row.id)
}

const currentTaskId = ref(null)
async function loadDetail(taskId) {
  currentTaskId.value = taskId
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await taskApi.detail(taskId, {
      resultPage: resultPage.page,
      resultSize: resultPage.size
    })
  } finally {
    detailLoading.value = false
  }
}

function changeResultPage(p) {
  resultPage.page = p
  loadDetail(currentTaskId.value)
}

function download(taskId) {
  window.open(resultApi.downloadUrl(taskId), '_blank')
}

function prettyDag(json) {
  if (!json) return ''
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

onMounted(loadTasks)
</script>

<template>
  <el-card class="page-card" shadow="never">
    <div class="toolbar">
      <el-radio-group v-model="query.status" @change="onFilterChange">
        <el-radio-button v-for="f in statusFilters" :key="String(f.value)" :value="f.value">
          {{ f.label }}
        </el-radio-button>
      </el-radio-group>
      <span class="spacer" />
      <el-button :icon="'Refresh'" @click="loadTasks">刷新</el-button>
    </div>

    <el-table :data="tasks" v-loading="loading" border>
      <el-table-column prop="id" label="任务ID" width="90" />
      <el-table-column prop="packageId" label="素材包" width="100" />
      <el-table-column prop="conversationId" label="对话" width="90" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="(TASK_STATUS[row.status] || {}).type">
            {{ (TASK_STATUS[row.status] || {}).label || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="160">
        <template #default="{ row }">
          <el-progress
            :percentage="row.totalCount ? Math.round((row.doneCount / row.totalCount) * 100) : 0"
            :status="row.status === 3 ? 'exception' : (row.status === 2 ? 'success' : undefined)"
          />
          <span class="progress-text">{{ row.doneCount }}/{{ row.totalCount }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="finishedAt" label="完成时间" width="170">
        <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="success" @click="download(row.id)">下载</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-sizes="[10, 20, 50]"
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      @current-change="loadTasks"
      @size-change="() => { query.page = 1; loadTasks() }"
    />
  </el-card>

  <!-- 任务详情 -->
  <el-drawer v-model="detailVisible" title="任务详情" size="60%">
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="任务ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="素材包">{{ detail.packageId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="(TASK_STATUS[detail.status] || {}).type">
              {{ (TASK_STATUS[detail.status] || {}).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总数">{{ detail.totalCount }}</el-descriptions-item>
          <el-descriptions-item label="完成">{{ detail.doneCount }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ formatTime(detail.finishedAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-head">
          <span>DAG 结构</span>
          <el-button size="small" type="success" :icon="'Download'" @click="download(detail.id)">打包下载</el-button>
        </div>
        <el-input type="textarea" :rows="6" readonly :model-value="prettyDag(detail.dagJson)" class="dag-json" />

        <div class="section-head"><span>处理结果</span></div>
        <div class="result-grid">
          <el-empty v-if="(detail.results || []).length === 0" description="暂无结果" :image-size="60" />
          <el-card v-for="r in detail.results" :key="r.id" class="result-card" shadow="hover" :body-style="{ padding: '0' }">
            <div class="thumb">
              <el-image
                v-if="r.previewUrl"
                :src="r.previewUrl"
                :preview-src-list="[r.previewUrl]"
                fit="contain"
                preview-teleported
              >
                <template #error><div class="thumb-fallback"><el-icon><Picture /></el-icon></div></template>
              </el-image>
              <div v-else class="thumb-fallback"><el-icon><PictureFilled /></el-icon></div>
            </div>
            <div class="result-meta">
              <div class="row">
                <strong>{{ r.skuId || '—' }}</strong>
                <el-tag size="small" :type="(RESULT_STATUS[r.status] || {}).type">
                  {{ (RESULT_STATUS[r.status] || {}).label || r.status }}
                </el-tag>
              </div>
              <div class="sub">支路 {{ r.branchId || '-' }}</div>
              <div v-if="r.generatedCopy" class="copy">{{ r.generatedCopy }}</div>
              <div v-if="r.errorMsg" class="err">{{ r.errorMsg }}</div>
            </div>
          </el-card>
        </div>

        <el-pagination
          class="pager"
          background
          small
          layout="prev, pager, next"
          :total="detail.totalCount || 0"
          :page-size="resultPage.size"
          :current-page="resultPage.page"
          @current-change="changeResultPage"
        />
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
.progress-text {
  font-size: 12px;
  color: #909399;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 20px 0 10px;
  font-weight: 600;
  color: #303133;
}
.dag-json :deep(textarea) {
  font-family: monospace;
  font-size: 12px;
}
.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 14px;
}
.result-card {
  overflow: hidden;
}
.thumb {
  height: 160px;
  background: #f5f7fa;
  display: flex;
  align-items: center;
  justify-content: center;
}
.thumb :deep(.el-image) {
  width: 100%;
  height: 100%;
}
.thumb-fallback {
  font-size: 36px;
  color: #c0c4cc;
}
.result-meta {
  padding: 8px 10px;
}
.result-meta .row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.result-meta .sub {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.result-meta .copy {
  font-size: 12px;
  color: #606266;
  margin-top: 6px;
  max-height: 48px;
  overflow: hidden;
}
.result-meta .err {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 6px;
}
</style>
