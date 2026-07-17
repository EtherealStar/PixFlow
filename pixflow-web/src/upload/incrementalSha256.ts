import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'

export const FILE_HASH_BLOCK_SIZE = 4 * 1024 * 1024

export interface IncrementalHashProgress {
  hashed: number
  total: number
}

/**
 * 对 Blob 原始字节连续更新同一个 SHA-256 状态，内存上界由 blockSize 控制。
 */
export async function hashBlobIncrementally(
  blob: Blob,
  onProgress?: (progress: IncrementalHashProgress) => void,
  signal?: AbortSignal,
  blockSize = FILE_HASH_BLOCK_SIZE
): Promise<string> {
  if (!Number.isInteger(blockSize) || blockSize <= 0) {
    throw new RangeError('blockSize 必须是正整数')
  }

  const digest = sha256.create()
  let cursor = 0
  while (cursor < blob.size) {
    throwIfAborted(signal)
    const end = Math.min(cursor + blockSize, blob.size)
    const buffer = await blob.slice(cursor, end).arrayBuffer()
    throwIfAborted(signal)
    digest.update(new Uint8Array(buffer))
    cursor = end
    onProgress?.({ hashed: cursor, total: blob.size })
  }
  throwIfAborted(signal)
  return bytesToHex(digest.digest())
}

function throwIfAborted(signal?: AbortSignal): void {
  if (!signal?.aborted) return
  throw new DOMException('上传已中止', 'AbortError')
}
