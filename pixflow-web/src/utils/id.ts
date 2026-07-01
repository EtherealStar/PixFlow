/**
 * 短 UUID 生成（不带连字符，便于日志展示）。
 * 使用 crypto.randomUUID 截取前 12 字符。
 */
export function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '').slice(0, 12)
  }
  return Math.random().toString(36).slice(2, 14)
}

/** 标准 UUID v4（用于 Idempotency-Key）。 */
export function newUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // Fallback (RFC4122 v4-ish)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

/** traceId 生成或透传。 */
export function newTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '').slice(0, 16)
  }
  return Math.random().toString(36).slice(2, 18)
}