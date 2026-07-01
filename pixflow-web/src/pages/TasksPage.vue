<script setup lang="ts">
import { ref } from 'vue'
import TaskProgressCard from '@/components/TaskProgressCard.vue'

const conversationId = 'demo-conv'
const taskId = ref('demo-task')

function newTask(): void {
  taskId.value = `t-${Date.now()}`
}
</script>

<template>
  <section class="tasks-page">
    <header>
      <h2>任务</h2>
      <el-button @click="newTask">演示用：新 taskId</el-button>
    </header>
    <TaskProgressCard :task-id="taskId" :conversation-id="conversationId" />
  </section>
</template>

<style scoped>
.tasks-page { padding: 16px; }
header { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
header h2 { margin: 0; }
</style>