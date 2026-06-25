import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/files' },
  {
    path: '/files',
    name: 'files',
    component: () => import('../views/FileManager.vue')
  },
  {
    path: '/conversation',
    name: 'conversation',
    component: () => import('../views/Conversation.vue')
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('../views/TaskResult.vue')
  }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
