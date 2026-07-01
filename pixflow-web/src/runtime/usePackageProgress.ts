import { ref, type Ref } from 'vue'
import { getStompConnection, type StompConnection } from '@/transport/ws'

/**
 * 素材包解压进度订阅。
 * 见 api.md `素材包进度频道`：`/topic/packages/{packageId}/progress`。
 */
export type PackageStatus = 'UPLOADED' | 'EXTRACTING' | 'READY' | 'PARTIAL' | 'FAILED'

export interface PackageProgressState {
  packageId: number
  status: PackageStatus
  extracted: number
  total: number
  updatedAt: number
}

export function createPackageProgress(opts: { packageId: number }) {
  const state: Ref<PackageProgressState> = ref({
    packageId: opts.packageId,
    status: 'UPLOADED',
    extracted: 0,
    total: 0,
    updatedAt: Date.now()
  })
  let stomp: StompConnection | null = null
  let subscription: { unsubscribe: () => void } | null = null

  function subscribe(): void {
    if (subscription) return
    if (!stomp) {
      const wsUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/progress`
      stomp = getStompConnection({ url: wsUrl })
    }
    const dest = `/topic/packages/${opts.packageId}/progress`
    subscription = stomp.subscribe(dest, (msg: { packageId: number; status: PackageStatus; extracted?: number; total?: number }) => {
      if (msg.packageId !== opts.packageId) return
      state.value = {
        packageId: opts.packageId,
        status: msg.status ?? state.value.status,
        extracted: msg.extracted ?? state.value.extracted,
        total: msg.total ?? state.value.total,
        updatedAt: Date.now()
      }
    })
  }

  function unsubscribe(): void {
    if (subscription) {
      subscription.unsubscribe()
      subscription = null
    }
  }

  return { state, subscribe, unsubscribe }
}

export type PackageProgress = ReturnType<typeof createPackageProgress>