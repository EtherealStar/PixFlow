<script setup>
import { useRoute, useRouter } from 'vue-router'
import { computed } from 'vue'

const route = useRoute()
const router = useRouter()
const active = computed(() => '/' + (route.path.split('/')[1] || 'files'))

function go(index) {
  router.push(index)
}
</script>

<template>
  <el-container class="app">
    <el-header class="app-header">
      <div class="brand">
        <el-icon><Picture /></el-icon>
        <span>PixFlow</span>
        <small>自然语言驱动的批量图片处理</small>
      </div>
      <el-menu :default-active="active" mode="horizontal" :ellipsis="false" @select="go" class="nav">
        <el-menu-item index="/files">
          <el-icon><Folder /></el-icon>文件管理
        </el-menu-item>
        <el-menu-item index="/conversation">
          <el-icon><ChatDotRound /></el-icon>对话处理
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>任务结果
        </el-menu-item>
      </el-menu>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style>
html, body, #app {
  height: 100%;
  margin: 0;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background: #f5f7fa;
}
.app {
  height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  padding: 0 24px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 600;
  color: #409eff;
  margin-right: 40px;
  white-space: nowrap;
}
.brand small {
  font-size: 12px;
  color: #909399;
  font-weight: 400;
}
.nav {
  flex: 1;
  border-bottom: none;
}
.app-main {
  padding: 20px;
  overflow: auto;
}
.page-card {
  background: #fff;
}
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.toolbar .spacer {
  flex: 1;
}
</style>
