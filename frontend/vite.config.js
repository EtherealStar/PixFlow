import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite dev server proxies all /api requests to the Spring Boot backend on :8080,
// so relative preview/download URLs returned by the backend work unchanged.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
