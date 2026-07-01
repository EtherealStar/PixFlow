<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listPackages } from '@/api/packages'
import { usePackagesStore } from '@/stores/packages'
import PackageUploader from '@/components/PackageUploader.vue'
import type { PackageDetail } from '@/types/upload'

const items = ref<PackageDetail[]>([])
const store = usePackagesStore()

onMounted(async () => {
  const page = await listPackages({ page: 0, size: 50 })
  items.value = page.items
  page.items.forEach((p) => store.upsert(p))
})
</script>

<template>
  <section class="packages-page">
    <header><h2>素材包</h2></header>
    <PackageUploader />

    <h3>已上传</h3>
    <el-table :data="items">
      <el-table-column prop="packageId" label="ID" width="80" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column prop="imageCount" label="图数" width="80" />
      <el-table-column prop="extractedCount" label="已解压" width="100" />
    </el-table>
  </section>
</template>

<style scoped>
.packages-page { padding: 16px; }
.packages-page header h2 { margin: 0 0 12px; }
.packages-page h3 { margin: 24px 0 12px; }
</style>