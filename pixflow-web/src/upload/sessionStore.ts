/**
 * uploadId 持久化（localStorage）。
 * 见 web.md §7.3。
 *
 * key: `pixflow.upload.session.{fileHash}`
 * value: { uploadId, filename, size, chunkSize, savedAt }
 * TTL: 7 天
 */

const PREFIX = 'pixflow.upload.session.'
const TTL_MS = 7 * 24 * 60 * 60 * 1000

export interface SessionEntry {
  uploadId: string
  filename: string
  size: number
  chunkSize: number
  savedAt: number
}

function keyFor(fileHash: string): string {
  return `${PREFIX}${fileHash}`
}

export function getSession(fileHash: string): SessionEntry | null {
  try {
    const raw = localStorage.getItem(keyFor(fileHash))
    if (!raw) return null
    const parsed = JSON.parse(raw) as SessionEntry
    if (Date.now() - parsed.savedAt > TTL_MS) {
      localStorage.removeItem(keyFor(fileHash))
      return null
    }
    return parsed
  } catch {
    return null
  }
}

export function setSession(fileHash: string, entry: SessionEntry): void {
  try {
    localStorage.setItem(keyFor(fileHash), JSON.stringify(entry))
  } catch {
    // localStorage 写入失败（隐私模式 / 配额），忽略
  }
}

export function deleteSession(fileHash: string): void {
  try {
    localStorage.removeItem(keyFor(fileHash))
  } catch {
    // 忽略
  }
}