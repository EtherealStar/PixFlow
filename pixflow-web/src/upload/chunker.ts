/**
 * 文件分片（5MB）。返回 Blob[]（用 file.slice()，不读字节到内存）。
 * 见 web.md §7.1。
 */
export const CHUNK_SIZE = 5 * 1024 * 1024 // 5MB

export function chunkFile(file: File, chunkSize: number = CHUNK_SIZE): Blob[] {
  const chunks: Blob[] = []
  let cursor = 0
  const total = file.size
  while (cursor < total) {
    const end = Math.min(cursor + chunkSize, total)
    chunks.push(file.slice(cursor, end))
    cursor = end
  }
  return chunks
}

export function expectedChunkCount(fileSize: number, chunkSize: number = CHUNK_SIZE): number {
  if (fileSize <= 0) return 0
  return Math.ceil(fileSize / chunkSize)
}