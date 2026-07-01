<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <section class="not-found">
    <h2>404</h2>
    <p>页面不存在</p>
    <el-button @click="router.push('/chat/new')">返回对话</el-button>
  </section>
</template>

<style scoped>
.not-found { padding: 48px; text-align: center; }
</style>