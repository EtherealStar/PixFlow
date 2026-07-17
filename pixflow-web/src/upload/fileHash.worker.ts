/// <reference lib="webworker" />

import { hashBlobIncrementally } from './incrementalSha256'

type HashWorkerRequest = { file: File }
type HashWorkerResponse =
  | { type: 'progress'; hashed: number; total: number }
  | { type: 'done'; hash: string }
  | { type: 'error'; message: string }

self.onmessage = async (event: MessageEvent<HashWorkerRequest>) => {
  try {
    const hash = await hashBlobIncrementally(event.data.file, ({ hashed, total }) => {
      self.postMessage({ type: 'progress', hashed, total } satisfies HashWorkerResponse)
    })
    self.postMessage({ type: 'done', hash } satisfies HashWorkerResponse)
  } catch (error) {
    const message = error instanceof Error ? error.message : '整文件哈希计算失败'
    self.postMessage({ type: 'error', message } satisfies HashWorkerResponse)
  }
}

export {}
