import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Rubrics 占位（Wave 6 范畴，V1 不在范围）。
 */
export const useRubricsStore = defineStore('rubrics', () => {
  const templates = ref<unknown[]>([])
  const runs = ref<unknown[]>([])

  return { templates, runs }
})