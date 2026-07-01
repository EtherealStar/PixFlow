import { describe, it, expect } from 'vitest'
import { chunkFile, expectedChunkCount, CHUNK_SIZE } from '@/upload/chunker'

class FakeFile {
  size: number
  name: string
  type: string
  private data: Uint8Array
  constructor(size: number) {
    this.size = size
    this.name = 'fake.bin'
    this.type = 'application/octet-stream'
    this.data = new Uint8Array(size)
    for (let i = 0; i < size; i++) this.data[i] = i & 0xff
  }
  slice(start: number, end: number): Blob {
    return new Blob([this.data.slice(start, end)])
  }
}

describe('chunkFile', () => {
  it('returns empty array for empty file', () => {
    const f = new FakeFile(0) as unknown as File
    expect(chunkFile(f)).toEqual([])
  })

  it('returns single chunk for small file', () => {
    const f = new FakeFile(1024) as unknown as File
    const chunks = chunkFile(f, 5 * 1024 * 1024)
    expect(chunks).toHaveLength(1)
  })

  it('returns ceil(N/CHUNK_SIZE) chunks for large file', () => {
    const f = new FakeFile(CHUNK_SIZE * 2 + 1024) as unknown as File
    const chunks = chunkFile(f, CHUNK_SIZE)
    expect(chunks).toHaveLength(3)
  })

  it('last chunk handles remainder', () => {
    const f = new FakeFile(CHUNK_SIZE + 100) as unknown as File
    const chunks = chunkFile(f, CHUNK_SIZE)
    expect(chunks).toHaveLength(2)
    expect(chunks[1]!.size).toBe(100)
  })
})

describe('expectedChunkCount', () => {
  it('0', () => expect(expectedChunkCount(0)).toBe(0))
  it('exact multiple', () => expect(expectedChunkCount(CHUNK_SIZE * 5)).toBe(5))
  it('with remainder', () => expect(expectedChunkCount(CHUNK_SIZE * 5 + 1)).toBe(6))
})