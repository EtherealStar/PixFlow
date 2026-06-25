import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 600000 // 同步执行任务可能较慢，给足超时
})

// 统一错误处理：后端错误响应结构为 { code, message, details }
http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const data = error.response && error.response.data
    const message = (data && (data.message || data.code)) || error.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

// ---------- 素材包（原始文件） ----------
export const assetApi = {
  list(params) {
    return http.get('/asset/package/list', { params })
  },
  detail(packageId) {
    return http.get(`/asset/package/${packageId}`)
  },
  upload(formData) {
    return http.post('/asset/package/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  remove(packageId) {
    return http.delete(`/asset/package/${packageId}`)
  }
}

// ---------- 加工结果（加工后文件） ----------
export const resultApi = {
  list(params) {
    return http.get('/asset/result/list', { params })
  },
  // 打包下载使用浏览器原生下载（绕过 axios），返回可点击的相对 URL
  downloadUrl(taskId) {
    return `/api/asset/result/download/${taskId}`
  },
  rawUrl(resultId) {
    return `/api/asset/result/${resultId}/raw`
  }
}

// ---------- 对话 ----------
export const conversationApi = {
  create() {
    return http.post('/conversation/create')
  },
  list(params) {
    return http.get('/conversation/list', { params })
  },
  messages(conversationId) {
    return http.get(`/conversation/${conversationId}/messages`)
  },
  send(conversationId, body) {
    return http.post(`/conversation/${conversationId}/send`, body)
  },
  confirm(conversationId, body) {
    return http.post(`/conversation/${conversationId}/confirm`, body)
  }
}

// ---------- 任务 ----------
export const taskApi = {
  list(params) {
    return http.get('/task/list', { params })
  },
  detail(taskId, params) {
    return http.get(`/task/${taskId}`, { params })
  }
}

export default http
