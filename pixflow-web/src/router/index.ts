import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/chat/new',
    meta: { requiresAuth: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginPage.vue'),
    meta: { standalone: true, public: true },
  },
  {
    path: '/chat/new',
    name: 'chat-new',
    component: () => import('@/pages/ChatPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/chat/:conversationId',
    name: 'chat',
    component: () => import('@/pages/ChatPage.vue'),
    props: true,
    meta: { requiresAuth: true },
  },
  {
    path: '/materials',
    name: 'materials',
    component: () => import('@/pages/MaterialsPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/materials/packages/:packageId/images/:imageId',
    name: 'material-image-detail',
    component: () => import('@/pages/MaterialImageDetailPage.vue'),
    props: true,
    meta: { requiresAuth: true },
  },
  {
    path: '/outputs',
    name: 'outputs',
    component: () => import('@/pages/OutputsPage.vue'),
    meta: { requiresAuth: true },
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

// 全局前置守卫：先恢复 JWT / refresh cookie，再判断登录态。
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.name === 'login') {
    if (auth.isAuthenticated) {
      const redirect = typeof to.query.redirect === 'string' ? to.query.redirect : '/chat/new'
      return redirect
    }
    return true
  }
  if (to.meta?.public) return true
  if (!to.meta?.requiresAuth) return true
  if (!auth.hasBootstrapped()) {
    await auth.bootstrap()
  }
  if (auth.isAuthenticated) return true
  return { name: 'login', query: { redirect: to.fullPath } }
})
