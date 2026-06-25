// 素材包状态映射（0 解析中 / 1 就绪 / 2 解析失败）
export const PACKAGE_STATUS = {
  0: { label: '解析中', type: 'info' },
  1: { label: '就绪', type: 'success' },
  2: { label: '解析失败', type: 'danger' }
}

// 任务状态映射（0 待执行 / 1 执行中 / 2 完成 / 3 失败）
export const TASK_STATUS = {
  0: { label: '待执行', type: 'info' },
  1: { label: '执行中', type: 'warning' },
  2: { label: '完成', type: 'success' },
  3: { label: '失败', type: 'danger' }
}

// 结果状态映射（0 待处理 / 1 成功 / 2 失败）
export const RESULT_STATUS = {
  0: { label: '待处理', type: 'info' },
  1: { label: '成功', type: 'success' },
  2: { label: '失败', type: 'danger' }
}

export function formatSize(bytes) {
  if (bytes === null || bytes === undefined) return '-'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = Number(bytes)
  let i = 0
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024
    i += 1
  }
  return `${value.toFixed(value < 10 && i > 0 ? 1 : 0)} ${units[i]}`
}

export function formatTime(value) {
  if (!value) return '-'
  // 后端返回的 LocalDateTime 形如 "2026-06-24T10:30:00"
  return String(value).replace('T', ' ').slice(0, 19)
}
