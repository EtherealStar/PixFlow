import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { PackageDetail } from '@/types/upload'

export const usePackagesStore = defineStore('packages', () => {
  const items = ref<Map<number, PackageDetail>>(new Map())

  function upsert(state: PackageDetail): void {
    items.value.set(state.packageId, state)
  }

  function get(packageId: number): PackageDetail | undefined {
    return items.value.get(packageId)
  }

  return { items, upsert, get }
})