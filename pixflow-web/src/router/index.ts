import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表（web.md §十一）
 *
 * 4 业务路由 + 2 占位 + 404：
 * - /                       → HomePage（概览 + 跳转入口）
 * - /chat/new 或 /chat/:cid → ChatPage
 * - /files?folder=:pid      → FilesPage（取代旧的 /packages 与 /packages/:pid）
 * - /tasks/:tid             → TaskDetailPage（取代旧的 /tasks）
 *
 * 占位路由（Wave 6 范畴）：
 * - /rubrics, /settings
 *
 * 鉴权守卫：未登录访问 /chat / /files / /tasks/:tid 时 redirect → /login。
 * 登录页 meta.standalone = true，App.vue 中全屏渲染不套三栏。
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/pages/HomePage.vue'),
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginPage.vue'),
    meta: { standalone: true, public: true },
  },
  {
    path: '/chat/:cid',
    name: 'chat',
    component: () => import('@/pages/ChatPage.vue'),
    props: true,
    meta: { requiresAuth: true },
  },
  {
    path: '/files',
    name: 'files',
    component: () => import('@/pages/FilesPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/tasks/:tid',
    name: 'task-detail',
    component: () => import('@/pages/TaskDetailPage.vue'),
    props: true,
    meta: { requiresAuth: true },
  },
  {
    path: '/rubrics',
    name: 'rubrics',
    component: () => import('@/pages/PlaceholderView.vue'),
    meta: { placeholder: true, title: 'Rubrics (Wave 6 范畴)' },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/pages/PlaceholderView.vue'),
    meta: { placeholder: true, title: 'Settings (Wave 6 范畴)' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/NotFoundPage.vue'),
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局前置守卫：未登录 → /login
router.beforeEach((to) => {
  if (to.meta?.public) return true
  if (!to.meta?.requiresAuth) return true
  // 动态 import auth store 避免循环依赖
  // 同步读 localStorage 兜底（store 初始化之前的导航场景）
  let token: string | null = null
  try { token = localStorage.getItem('pixflow.auth.token') } catch { /* noop */ }
  if (token) return true
  return { name: 'login', query: { redirect: to.fullPath } }
})