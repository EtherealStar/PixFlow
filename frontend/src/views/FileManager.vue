<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { assetApi, resultApi, taskApi } from '../api'
import { PACKAGE_STATUS, RESULT_STATUS, formatSize, formatTime } from '../utils/format'

const tab = ref('original')

/* ============ 原始文件子界面 ============ */
const pkgLoading = ref(false)
const packages = ref([])
const pkgQuery = reactive({ page: 1, size: 10, sortBy: 'created_at', order: 'desc' })
const pkgTotal = ref(0)

async function loadPackages() {
  pkgLoading.value = true
  try {
    const res = await assetApi.list({ ...pkgQuery })
    packages.value = res.records || []
    pkgTotal.value = res.total || 0
  } finally {
    pkgLoading.value = false
  }
}

function onSortChange({ prop, order }) {
  if (!order) return
  const map = { name: 'name', size: 'size', createdAt: 'created_at' }
  pkgQuery.sortBy = map[prop] || 'created_at'
  pkgQuery.order = order === 'ascending' ? 'asc' : 'desc'
  pkgQuery.page = 1
  loadPackages()
}

/* ---- 上传 ---- */
const uploadVisible = ref(false)
const uploading = ref(false)
const zipFile = ref(null)
const docFile = ref(null)
const uploadResult = ref(null)

function onZipChange(file) {
  zipFile.value = file.raw
}
function onDocChange(file) {
  docFile.value = file.raw
}

async function doUpload() {
  if (!zipFile.value) {
    ElMessage.warning('请选择 zip 素材包')
    return
  }
  const fd = new FormData()
  fd.append('zip_file', zipFile.value)
  if (docFile.value) fd.append('doc_file', docFile.value)
  uploading.value = true
  try {
    uploadResult.value = await assetApi.upload(fd)
    ElMessage.success('上传完成')
    loadPackages()
  } finally {
    uploading.value = false
  }
}

function resetUpload() {
  zipFile.value = null
  docFile.value = null
  uploadResult.value = null
}

/* ---- 详情 ---- */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await assetApi.detail(row.id)
  } finally {
    detailLoading.value = false
  }
}

/* ---- 删除 ---- */
async function removePackage(row) {
  try {
    await ElMessageBox.confirm(`确认删除素材包「${row.name}」？该操作将清理原始文件。`, '删除确认', {
      type: 'warning'
    })
  } catch {
    return
  }
  const report = await assetApi.remove(row.id)
  if (report.deleted) {
    ElMessage.success(`已删除，清理物理文件 ${report.deletedFileCount} 个`)
  } else {
    ElMessage.warning('删除未完成，请查看失败文件')
  }
  loadPackages()
}

/* ============ 加工后文件子界面 ============ */
const resultLoading = ref(false)
const results = ref([])
const resultQuery = reactive({ page: 1, size: 12, taskId: null })
const resultTotal = ref(0)
const taskOptions = ref([])

async function loadResults() {
  resultLoading.value = true
  try {
    const params = { page: resultQuery.page, size: resultQuery.size }
    if (resultQuery.taskId) params.taskId = resultQuery.taskId
    const res = await resultApi.list(params)
    results.value = res.records || []
    resultTotal.value = res.total || 0
  } finally {
    resultLoading.value = false
  }
}

async function loadTaskOptions() {
  const res = await taskApi.list({ page: 1, size: 100 })
  taskOptions.value = res.records || []
}

function download(taskId) {
  if (!taskId) {
    ElMessage.warning('请先选择一个任务')
    return
  }
  window.open(resultApi.downloadUrl(taskId), '_blank')
}

function onTabChange(name) {
  if (name === 'processed' && results.value.length === 0) {
    loadTaskOptions()
    loadResults()
  }
}

onMounted(loadPackages)
</script>

<template>
  <el-card class="page-card" shadow="never">
    <el-tabs v-model="tab" @tab-change="onTabChange">
      <!-- ============ 原始文件 ============ -->
      <el-tab-pane label="原始文件" name="original">
        <div class="toolbar">
          <el-button type="primary" :icon="'Upload'" @click="uploadVisible = true; resetUpload()">
            上传素材包
          </el-button>
          <span class="spacer" />
          <el-button :icon="'Refresh'" @click="loadPackages">刷新</el-button>
        </div>

        <el-table :data="packages" v-loading="pkgLoading" @sort-change="onSortChange" border>
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="name" label="包名" sortable="custom" min-width="220" show-overflow-tooltip />
          <el-table-column prop="size" label="大小" width="120" sortable="custom">
            <template #default="{ row }">{{ formatSize(row.size) }}</template>
          </el-table-column>
          <el-table-column prop="imageCount" label="图片数" width="100" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="(PACKAGE_STATUS[row.status] || {}).type">
                {{ (PACKAGE_STATUS[row.status] || {}).label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="180" sortable="custom">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="danger" @click="removePackage(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="pager"
          background
          layout="total, sizes, prev, pager, next"
          :total="pkgTotal"
          :page-sizes="[10, 20, 50, 100]"
          v-model:current-page="pkgQuery.page"
          v-model:page-size="pkgQuery.size"
          @current-change="loadPackages"
          @size-change="() => { pkgQuery.page = 1; loadPackages() }"
        />
      </el-tab-pane>

      <!-- ============ 加工后文件 ============ -->
      <el-tab-pane label="加工后文件" name="processed">
        <div class="toolbar">
          <el-select
            v-model="resultQuery.taskId"
            placeholder="按任务筛选"
            clearable
            style="width: 260px"
            @change="() => { resultQuery.page = 1; loadResults() }"
          >
            <el-option
              v-for="t in taskOptions"
              :key="t.id"
              :label="`任务 #${t.id}（包 ${t.packageId}）`"
              :value="t.id"
            />
          </el-select>
          <el-button type="success" :icon="'Download'" :disabled="!resultQuery.taskId" @click="download(resultQuery.taskId)">
            打包下载选中任务
          </el-button>
          <span class="spacer" />
          <el-button :icon="'Refresh'" @click="loadResults">刷新</el-button>
        </div>

        <div v-loading="resultLoading" class="result-grid">
          <el-empty v-if="!resultLoading && results.length === 0" description="暂无加工结果" />
          <el-card v-for="r in results" :key="r.id" class="result-card" shadow="hover" :body-style="{ padding: '0' }">
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
              <div v-else class="thumb-fallback">
                <el-icon><PictureFilled /></el-icon>
              </div>
            </div>
            <div class="result-meta">
              <div class="row">
                <strong>{{ r.skuId || '—' }}</strong>
                <el-tag size="small" :type="(RESULT_STATUS[r.status] || {}).type">
                  {{ (RESULT_STATUS[r.status] || {}).label || r.status }}
                </el-tag>
              </div>
              <div class="sub">任务 #{{ r.taskId }} · 支路 {{ r.branchId || '-' }}</div>
              <div v-if="r.generatedCopy" class="copy">{{ r.generatedCopy }}</div>
              <div v-if="r.errorMsg" class="err">{{ r.errorMsg }}</div>
            </div>
          </el-card>
        </div>

        <el-pagination
          class="pager"
          background
          layout="total, sizes, prev, pager, next"
          :total="resultTotal"
          :page-sizes="[12, 24, 48]"
          v-model:current-page="resultQuery.page"
          v-model:page-size="resultQuery.size"
          @current-change="loadResults"
          @size-change="() => { resultQuery.page = 1; loadResults() }"
        />
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- 上传弹窗 -->
  <el-dialog v-model="uploadVisible" title="上传素材包" width="520px">
    <el-form label-width="120px">
      <el-form-item label="图片压缩包" required>
        <el-upload :auto-upload="false" :limit="1" accept=".zip" :on-change="onZipChange">
          <el-button :icon="'FolderAdd'">选择 zip</el-button>
        </el-upload>
      </el-form-item>
      <el-form-item label="文案文档（选填）">
        <el-upload :auto-upload="false" :limit="1" accept=".xls,.xlsx,.csv" :on-change="onDocChange">
          <el-button :icon="'Document'">选择 Excel/CSV</el-button>
        </el-upload>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="uploadResult"
      :title="`包 #${uploadResult.packageId} ${uploadResult.name} — 识别图片 ${uploadResult.imageCount} 张`"
      :type="uploadResult.status === 1 ? 'success' : 'error'"
      :closable="false"
      show-icon
    >
      <template #default>
        <div v-if="uploadResult.failureReason">原因：{{ uploadResult.failureReason }}</div>
        <div v-if="uploadResult.skippedFiles && uploadResult.skippedFiles.length">
          跳过 {{ uploadResult.skippedFiles.length }} 个文件
        </div>
      </template>
    </el-alert>

    <template #footer>
      <el-button @click="uploadVisible = false">关闭</el-button>
      <el-button type="primary" :loading="uploading" @click="doUpload">开始上传</el-button>
    </template>
  </el-dialog>

  <!-- 详情弹窗 -->
  <el-dialog v-model="detailVisible" title="素材包详情" width="640px">
    <div v-loading="detailLoading">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="包名">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="大小">{{ formatSize(detail.size) }}</el-descriptions-item>
          <el-descriptions-item label="图片数">{{ detail.imageCount }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="(PACKAGE_STATUS[detail.status] || {}).type">
              {{ (PACKAGE_STATUS[detail.status] || {}).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.images || []" border size="small" max-height="320" style="margin-top: 16px">
          <el-table-column prop="imageId" label="图片ID" width="90" />
          <el-table-column prop="skuId" label="SKU" width="140" show-overflow-tooltip />
          <el-table-column prop="originalPath" label="相对路径" show-overflow-tooltip />
        </el-table>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
  min-height: 120px;
}
.result-card {
  overflow: hidden;
}
.thumb {
  height: 180px;
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
  font-size: 40px;
  color: #c0c4cc;
}
.result-meta {
  padding: 10px 12px;
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
