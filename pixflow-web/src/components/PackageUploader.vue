<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createUploadJob, type UploadJobHandle } from '@/upload/uploadJob'
import { useUploadJobsStore } from '@/stores/uploadJobs'
import { formatBytes } from '@/utils/format'
import type { ApiError } from '@/types/api'

const file = ref<File | null>(null)
const job = ref<UploadJobHandle | null>(null)
const store = useUploadJobsStore()

watch(file, (f) => {
  if (!f) return
  job.value = createUploadJob({
    file: f,
    onProgress: () => {
      // 进度已在 state 内
    },
    onError: (err: ApiError) => {
      ElMessage.error(`上传失败：${err.message}`)
    },
    onDedup: (kind, packageId) => {
      if (kind === 'READY') {
        ElMessage.success(`素材包已存在（packageId=${packageId}）`)
      } else {
        ElMessage.warning('另一客户端正在上传该压缩包')
      }
    },
    onDone: (packageId) => {
      ElMessage.success(`上传完成（packageId=${packageId}）`)
    }
  })
  store.add(job.value.state.value)
  void job.value.run()
})

function onFileChange(fileObj: { raw?: File } | undefined): void {
  file.value = fileObj?.raw ?? null
}

async function handleCancel(): Promise<void> {
  if (job.value) {
    await job.value.cancel()
    ElMessage.info('已取消上传')
  }
}

async function handleRetry(): Promise<void> {
  if (job.value) {
    await job.value.retry()
  }
}
</script>

<template>
  <div class="package-uploader">
    <el-upload
      :auto-upload="false"
      :show-file-list="false"
      :on-change="onFileChange"
      accept=".zip"
    >
      <el-button type="primary">选择 zip 文件</el-button>
    </el-upload>

    <div v-if="job" class="progress-panel">
      <header class="head">
        <span>{{ job.state.value.filename }}</span>
        <span class="phase">{{ job.state.value.phase }}</span>
      </header>
      <div class="bytes" v-if="job.state.value.progress">
        上传 {{ formatBytes(job.state.value.progress.uploaded) }} / {{ formatBytes(job.state.value.progress.total) }}
      </div>
      <div class="chunks">分片 {{ job.state.value.uploadedChunks }} / {{ job.state.value.totalChunks }}</div>
      <div v-if="job.state.value.error" class="error">{{ job.state.value.error.message }}</div>
      <div class="actions">
        <el-button v-if="job.state.value.phase === 'uploading'" @click="handleCancel">取消</el-button>
        <el-button v-if="job.state.value.phase === 'error'" type="primary" @click="handleRetry">重试</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.package-uploader {
  padding: 16px;
  background: var(--color-bg);
  border-radius: 6px;
  border: 1px solid var(--color-border);
}
.progress-panel { margin-top: 12px; }
.head { display: flex; justify-content: space-between; font-size: 14px; }
.phase {
  text-transform: uppercase;
  font-size: 12px;
  padding: 2px 6px;
  background: var(--color-bg-soft);
  border-radius: 4px;
  color: var(--color-text-mute);
}
.bytes, .chunks {
  font-size: 12px;
  color: var(--color-text-mute);
  margin: 6px 0;
}
.error { color: var(--color-error); font-size: 12px; margin: 6px 0; }
.actions { display: flex; gap: 8px; justify-content: flex-end; }
</style>