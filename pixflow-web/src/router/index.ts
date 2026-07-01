import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/chat/new'
  },
  {
    path: '/chat/:cid',
    name: 'chat',
    component: () => import('@/pages/ChatPage.vue'),
    props: true,
    children: [
      {
        path: 'tasks/:tid',
        name: 'chat-task',
        component: () => import('@/components/TaskDrawer.vue'),
        props: true
      }
    ]
  },
  {
    path: '/packages',
    name: 'packages',
    component: () => import('@/pages/PackagesPage.vue')
  },
  {
    path: '/packages/:pid',
    name: 'package-detail',
    component: () => import('@/pages/PackageDetailPage.vue'),
    props: true
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('@/pages/TasksPage.vue')
  },
  {
    path: '/rubrics',
    name: 'rubrics',
    component: () => import('@/pages/PlaceholderView.vue'),
    meta: { placeholder: true, title: 'Rubrics (Wave 6 范畴)' }
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/pages/PlaceholderView.vue'),
    meta: { placeholder: true, title: 'Settings (Wave 6 范畴)' }
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/pages/NotFoundPage.vue')
  }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})