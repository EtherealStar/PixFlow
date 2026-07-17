function bytesToHex(bytes: ArrayBuffer): string {
  const view = new Uint8Array(bytes)
  let value = ''
  for (const byte of view) value += byte.toString(16).padStart(2, '0')
  return value
}

async function digestHex(buffer: ArrayBuffer): Promise<string> {
  return bytesToHex(await crypto.subtle.digest('SHA-256', buffer))
}

export interface HashProgress {
  hashed: number
  total: number
}

type HashWorkerResponse =
  | { type: 'progress'; hashed: number; total: number }
  | { type: 'done'; hash: string }
  | { type: 'error'; message: string }

/**
 * 在专用 Worker 中计算标准整文件 SHA-256，避免大文件哈希阻塞 Vue 主线程。
 */
export function sha256File(
  file: File,
  onProgress?: (progress: HashProgress) => void,
  signal?: AbortSignal
): Promise<string> {
  if (signal?.aborted) return Promise.reject(abortError())

  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./fileHash.worker.ts', import.meta.url), { type: 'module' })
    let settled = false

    const finish = (action: () => void) => {
      if (settled) return
      settled = true
      signal?.removeEventListener('abort', onAbort)
      worker.terminate()
      action()
    }
    const onAbort = () => finish(() => reject(abortError()))

    worker.onmessage = (event: MessageEvent<HashWorkerResponse>) => {
      const message = event.data
      if (message.type === 'progress') {
        if (!settled) onProgress?.({ hashed: message.hashed, total: message.total })
        return
      }
      if (message.type === 'done') {
        finish(() => resolve(message.hash))
        return
      }
      finish(() => reject(new Error(message.message)))
    }
    worker.onerror = (event) => finish(() => reject(new Error(event.message || '整文件哈希计算失败')))
    signal?.addEventListener('abort', onAbort, { once: true })
    worker.postMessage({ file })
  })
}

/** 单个分片大小固定，因此继续用 Web Crypto 一次性计算摘要。 */
export async function sha256Blob(blob: Blob): Promise<string> {
  return digestHex(await blob.arrayBuffer())
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function abortError(): DOMException {
  return new DOMException('上传已中止', 'AbortError')
}
