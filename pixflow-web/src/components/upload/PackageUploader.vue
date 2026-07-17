<script setup lang="ts">
import { ref } from 'vue'
import UploadDropzone from '@/components/upload/UploadDropzone.vue'
import UploadJobCard from '@/components/upload/UploadJobCard.vue'
import IconPlus from '@/components/icons/IconPlus.vue'
import { useUploadJobsStore } from '@/stores/uploadJobs'
import { useToastStore } from '@/stores/toast'

/**
 * PackageUploader — 素材包上传器（web.md §十三 / §7.3）
 *
 * 职责：
 * - 用 UploadDropzone 选择文件 → 创建整文件上传 job
 * - 列出当前 uploadJobs 中的活跃 job
 * - 失败 job 提供重试入口
 */
const uploadJobs = useUploadJobsStore()
const toast = useToastStore()
const compact = ref(false)

function pickFiles(files: File[]): void {
  for (const f of files) {
    void uploadJobs.startWholeFile(f).catch((err: unknown) => {
      const msg = err instanceof Error ? err.message : String(err)
      toast.push({ variant: 'danger', message: `上传失败：${msg}` })
    })
  }
  toast.push({ variant: 'info', message: `已添加 ${files.length} 个文件到上传队列` })
}
</script>

<template>
  <section class="package-uploader flex flex-col gap-3">
    <header class="flex items-center justify-between px-4 pt-3">
      <h3 class="text-sm font-medium text-fg-primary">
        上传素材
      </h3>
      <button
        type="button"
        class="text-fg-muted hover:text-fg-primary"
        :aria-label="compact ? '展开' : '收起'"
        @click="compact = !compact"
      >
        <IconPlus
          :size="16"
          :class="compact ? 'rotate-45 transition-transform' : 'transition-transform'"
        />
      </button>
    </header>

    <UploadDropzone
      v-if="!compact"
      @select="pickFiles"
    />

    <div class="flex flex-col gap-2 px-4 pb-4">
      <UploadJobCard
        v-for="job in uploadJobs.activeJobs"
        :key="job.jobId"
        :job="job"
        @pause="(id: string) => uploadJobs.pause(id)"
        @resume="(id: string) => uploadJobs.resume(id)"
        @cancel="(id: string) => uploadJobs.cancel(id)"
        @retry="(id: string) => uploadJobs.retry(id)"
      />
    </div>
  </section>
</template>
