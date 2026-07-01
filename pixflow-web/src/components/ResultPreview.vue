<script setup lang="ts">
import { ref } from 'vue'
import { listTaskResults, getResultDownload, type TaskResult } from '@/api/tasks'
import { ElMessage } from 'element-plus'

const props = defineProps<{
  taskId: string
}>()

const results = ref<TaskResult[]>([])
const loading = ref(false)
const previewUrl = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await listTaskResults(props.taskId, { page: 0, size: 50 })
    results.value = page.items.filter((r) => r.status === 'SUCCESS')
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message ?? '加载结果失败')
  } finally {
    loading.value = false
  }
}

async function openResult(r: TaskResult): Promise<void> {
  try {
    const h = await getResultDownload(props.taskId, r.resultId)
    previewUrl.value = h.url
    window.open(h.url, '_blank')
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message ?? '获取下载链接失败')
  }
}

async function downloadBundle(): Promise<void> {
  try {
    const { getBundleDownload } = await import('@/api/tasks')
    const h = await getBundleDownload(props.taskId)
    window.open(h.url, '_blank')
  } catch (e) {
    const err = e as { message?: string }
    ElMessage.error(err.message ?? '获取批量下载链接失败')
  }
}

defineExpose({ load })
</script>

<template>
  <div class="result-preview">
    <header class="head">
      <h4>结果预览</h4>
      <div>
        <el-button size="small" :loading="loading" @click="load">加载结果</el-button>
        <el-button size="small" @click="downloadBundle">打包下载</el-button>
      </div>
    </header>
    <div class="grid" v-if="results.length > 0">
      <a v-for="r in results" :key="r.resultId" class="thumb" :href="previewUrl ?? '#'" target="_blank" @click.prevent="openResult(r)">
        <img :src="previewUrl" v-if="previewUrl === previewUrl" alt="" />
        <div class="placeholder" v-else>结果 {{ r.resultId.slice(0, 6) }}</div>
      </a>
    </div>
    <el-empty v-else-if="!loading" description="暂无结果" />
  </div>
</template>

<style scoped>
.result-preview { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 8px; }
.thumb { aspect-ratio: 1; border: 1px solid var(--color-border); border-radius: 4px; overflow: hidden; cursor: pointer; }
.placeholder { display: flex; align-items: center; justify-content: center; height: 100%; background: var(--color-bg-soft); font-size: 12px; color: var(--color-text-mute); }
</style>