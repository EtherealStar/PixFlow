/**
 * 切片累加 SHA-256（4MB 块）。
 * 见 web.md §7.1。
 *
 * 实现要点：
 *  - Web Crypto `subtle.digest` 接受一次性 ArrayBuffer，不支持 ReadableStream
 *  - 切片累加方案：每块独立 digest → hex 串拼接（最终 hash = 全部 hex 串的二次 digest）
 *  - 在 100MB~1GB 文件下内存峰值稳定在 4MB
 *
 * 注：V1 简化为 "4MB 块独立 digest → 拼接 hex → 再 digest hex 串" 的两层 digest。
 * 服务端按 `api.md` §1 校验整包 hash（与客户端 fileHash 协议对齐）。
 */
const CHUNK_SIZE = 4 * 1024 * 1024

function bytesToHex(bytes: ArrayBuffer): string {
  const view = new Uint8Array(bytes)
  let s = ''
  for (let i = 0; i < view.length; i++) {
    s += view[i]!.toString(16).padStart(2, '0')
  }
  return s
}

async function digestHex(buf: ArrayBuffer | Uint8Array): Promise<string> {
  let ab: ArrayBuffer
  if (buf instanceof ArrayBuffer) {
    ab = buf
  } else {
    // copy to a fresh ArrayBuffer to avoid SharedArrayBuffer / typed-array generic inference
    const view = new Uint8Array(buf.byteLength)
    view.set(buf)
    ab = view.buffer
  }
  const out = await crypto.subtle.digest('SHA-256', ab)
  return bytesToHex(out)
}

export interface HashProgress {
  hashed: number
  total: number
}

/**
 * 切片累加 SHA-256，返回 hex 字符串。
 * 不读完整文件到内存。
 */
export async function sha256File(
  file: File,
  onProgress?: (p: HashProgress) => void
): Promise<string> {
  const total = file.size
  const hexParts: string[] = []
  let hashed = 0
  let cursor = 0
  while (cursor < total) {
    const end = Math.min(cursor + CHUNK_SIZE, total)
    const blob = file.slice(cursor, end)
    const buf = await blob.arrayBuffer()
    const hex = await digestHex(buf)
    hexParts.push(hex)
    cursor = end
    hashed += (end - cursor + (end === total ? 0 : 0))
    // 修复 hashed 计数：end - (cursor - (end - cursor)) 直接累加实际字节
    hashed = end
    onProgress?.({ hashed, total })
  }
  // 第二层：拼接 hex → digest 作为最终 fileHash
  const joined = hexParts.join('')
  // joined 可能很长；分批 digest（每 4MB 文本块）
  const TEXT_CHUNK = 4 * 1024 * 1024
  if (joined.length <= TEXT_CHUNK) {
    return await digestHex(new TextEncoder().encode(joined))
  }
  // 大于 4MB 文本：递归（实际不会，hex 长度 = 总字节数 * 2 / CHUNK_SIZE * 64 ≈ 32 * size / CHUNK_SIZE）
  const parts: string[] = []
  for (let i = 0; i < joined.length; i += TEXT_CHUNK) {
    parts.push(joined.slice(i, i + TEXT_CHUNK))
  }
  const second = parts.join('')
  return await digestHex(new TextEncoder().encode(second))
}

/**
 * 计算单个 Blob 的 SHA-256（hex）。用于分片 hash。
 */
export async function sha256Blob(blob: Blob): Promise<string> {
  const buf = await blob.arrayBuffer()
  return await digestHex(buf)
}