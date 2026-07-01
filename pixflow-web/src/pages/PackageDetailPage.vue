<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getPackage } from '@/api/packages'
import PackageExtractionProgress from '@/components/PackageExtractionProgress.vue'
import type { PackageDetail } from '@/types/upload'

const route = useRoute()
const pid = Number(route.params.pid)
const detail = ref<PackageDetail | null>(null)

onMounted(async () => {
  detail.value = await getPackage(pid)
})
</script>

<template>
  <section class="package-detail">
    <header><h2>素材包 #{{ pid }}</h2></header>
    <div v-if="detail" class="info">
      <div>名称：{{ detail.name }}</div>
      <div>状态：{{ detail.status }}</div>
      <div>总数：{{ detail.imageCount }} / 已解压：{{ detail.extractedCount }}</div>
    </div>
    <PackageExtractionProgress :package-id="pid" />
  </section>
</template>

<style scoped>
.package-detail { padding: 16px; }
.info { display: flex; gap: 16px; font-size: 14px; margin: 12px 0; }
</style>