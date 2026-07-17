import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { sha256Blob } from '@/upload/hasher'
import { FILE_HASH_BLOCK_SIZE, hashBlobIncrementally } from '@/upload/incrementalSha256'

describe('incremental whole-file SHA-256', () => {
  it('matches the empty-file standard vector', async () => {
    await expect(hashBlobIncrementally(new Blob([]))).resolves.toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
    )
  })

  it('matches the abc standard vector', async () => {
    await expect(hashBlobIncrementally(new Blob(['abc']))).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    )
  })

  it('keeps one digest state across the 4 MiB boundary', async () => {
    const bytes = new Uint8Array(FILE_HASH_BLOCK_SIZE + 37)
    for (let index = 0; index < bytes.length; index++) bytes[index] = index % 251
    const oracle = createHash('sha256').update(bytes).digest('hex')
    const progress: number[] = []

    const actual = await hashBlobIncrementally(new Blob([bytes]), ({ hashed }) => progress.push(hashed))

    expect(actual).toBe(oracle)
    expect(progress).toEqual([FILE_HASH_BLOCK_SIZE, bytes.length])
  })
})

describe('sha256Blob', () => {
  it('computes the standard digest for one upload chunk', async () => {
    await expect(sha256Blob(new Blob(['abc']))).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    )
  })
})
