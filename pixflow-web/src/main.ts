import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { installDevConsoleGuard } from './utils/devConsoleGuard'
import './styles/global.css'

/**
 * 应用入口
 *
 * 重构说明（2026-07-01，R1 基础设施完成）：
 * - 移除旧视觉库全部引用（web.md §二十三 决策）
 * - 移除旧视觉库全局样式表，避免无作用域样式残留
 * - 视觉层由 Tailwind + design token + 自绘组件承载（见 R3）
 * - 鉴权走 radix-vue headless（见 R3.3）+ 自绘 AppDialog/AppDropdownMenu 等
 */
const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

void useAuthStore(pinia).bootstrap()

if (__DEV__) {
  installDevConsoleGuard()
}

app.mount('#app')
