import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { installDevConsoleGuard } from './utils/devConsoleGuard'
import './styles/global.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)

if (__DEV__) {
  installDevConsoleGuard()
}

app.mount('#app')