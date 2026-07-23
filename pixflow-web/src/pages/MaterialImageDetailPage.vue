<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { getOriginalImage, type OriginalImageDetail } from '@/api/materials'
import {
  getVisualFacts,
  reanalyzeVisualFacts,
  replaceVisualFacts,
  type VisualFacts,
  type VisualFactsView
} from '@/api/vision'
import AppButton from '@/components/ui/AppButton.vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import { ApiError } from '@/types/api'

const POLL_INTERVAL_MS = 2_000

const route = useRoute()
const router = useRouter()
const detail = ref<OriginalImageDetail | null>(null)
const loading = ref(false)
const factsView = ref<VisualFactsView | null>(null)
const draft = ref<VisualFacts | null>(null)
const saving = ref(false)
const reanalyzing = ref(false)
const dirty = ref(false)
const error = ref('')
const conflict = ref(false)
const leaveDialogOpen = ref(false)
const pendingPath = ref<string | null>(null)
let detailGeneration = 0
let factsGeneration = 0
let detailAbort: AbortController | null = null
let factsAbort: AbortController | null = null
let pollTimer: number | null = null

const skuId = computed(() => detail.value?.image.skuId ?? null)
const analysisActive = computed(() => {
  const status = factsView.value?.analysisStatus
  return status === 'PENDING' || status === 'RUNNING'
})
const canEdit = computed(() => draft.value !== null && !analysisActive.value)

function emptyFacts(): VisualFacts {
  return {
    common: {
      categoryAppearance: '',
      dominantColors: [],
      visibleMaterials: [],
      shapes: [],
      visibleComponents: [],
      patterns: [],
      visibleText: [],
      background: '',
      viewTypes: []
    },
    attributes: [],
    limitations: [],
    conflicts: []
  }
}

async function load(): Promise<void> {
  detailAbort?.abort()
  const controller = new AbortController()
  detailAbort = controller
  const requestGeneration = ++detailGeneration
  loading.value = true
  error.value = ''
  try {
    const next = await getOriginalImage(
      Number(route.params.packageId),
      String(route.params.imageId),
      controller.signal
    )
    if (requestGeneration !== detailGeneration) return
    detail.value = next
    if (next.image.skuId) await loadFacts(next.image.skuId)
    else resetFactsRuntime()
  } catch (cause: unknown) {
    if (requestGeneration === detailGeneration && !controller.signal.aborted) {
      error.value = errorMessage(cause, '暂时无法加载素材')
    }
  } finally {
    if (requestGeneration === detailGeneration) loading.value = false
    if (detailAbort === controller) detailAbort = null
  }
}

async function loadFacts(currentSku: string): Promise<void> {
  stopFactsRequest()
  const requestGeneration = ++factsGeneration
  const controller = new AbortController()
  factsAbort = controller
  error.value = ''
  try {
    const next = await getVisualFacts(Number(route.params.packageId), currentSku, controller.signal)
    if (requestGeneration !== factsGeneration) return
    applyFactsView(next)
  } catch {
    if (requestGeneration !== factsGeneration || controller.signal.aborted) return
    error.value = '暂时无法获取分析状态'
  } finally {
    if (factsAbort === controller) factsAbort = null
  }
}

function applyFactsView(next: VisualFactsView): void {
  factsView.value = next
  conflict.value = false
  // 活跃分析没有旧文档时只展示进度；终态无文档则提供完整空表单。
  draft.value = next.facts ? structuredClone(next.facts) : analysisIsActive(next) ? null : emptyFacts()
  dirty.value = false
  schedulePoll(next)
}

function schedulePoll(view: VisualFactsView): void {
  clearPollTimer()
  if (!analysisIsActive(view)) return
  const expectedSku = view.skuId
  const requestGeneration = factsGeneration
  pollTimer = window.setTimeout(() => {
    void pollFacts(expectedSku, requestGeneration)
  }, POLL_INTERVAL_MS)
}

async function pollFacts(expectedSku: string, requestGeneration: number): Promise<void> {
  if (requestGeneration !== factsGeneration || skuId.value !== expectedSku) return
  const controller = new AbortController()
  factsAbort = controller
  try {
    const next = await getVisualFacts(Number(route.params.packageId), expectedSku, controller.signal)
    if (requestGeneration === factsGeneration && skuId.value === expectedSku) applyFactsView(next)
  } catch {
    if (!controller.signal.aborted && requestGeneration === factsGeneration) {
      error.value = '暂时无法获取分析状态'
      if (factsView.value) schedulePoll(factsView.value)
    }
  } finally {
    if (factsAbort === controller) factsAbort = null
  }
}

function markDirty(): void {
  if (canEdit.value) dirty.value = true
}

function cancelChanges(): void {
  const current = factsView.value?.facts
  draft.value = current ? structuredClone(current) : emptyFacts()
  dirty.value = false
  conflict.value = false
  error.value = ''
}

async function save(): Promise<void> {
  if (!factsView.value || !draft.value || analysisActive.value) return
  saving.value = true
  error.value = ''
  conflict.value = false
  try {
    const normalized = normalizeFacts(draft.value)
    const next = await replaceVisualFacts(
      Number(route.params.packageId),
      factsView.value.skuId,
      factsView.value.version,
      normalized
    )
    applyFactsView(next)
  } catch (cause: unknown) {
    conflict.value = cause instanceof ApiError && cause.errorCode === 'VISUAL_FACTS_VERSION_CONFLICT'
    error.value = conflict.value
      ? '事实已被其他操作更新。可加载最新版本后重新编辑。'
      : errorMessage(cause, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

async function reanalyze(): Promise<void> {
  if (!factsView.value || reanalyzing.value || dirty.value || analysisActive.value) return
  reanalyzing.value = true
  error.value = ''
  try {
    const next = await reanalyzeVisualFacts(
      Number(route.params.packageId),
      factsView.value.skuId,
      factsView.value.analysisGeneration,
      crypto.randomUUID()
    )
    applyFactsView(next)
  } catch (cause: unknown) {
    error.value = cause instanceof ApiError && cause.errorCode === 'VISUAL_FACTS_GENERATION_CONFLICT'
      ? '分析状态已变化，请加载最新状态后重试'
      : errorMessage(cause, '重新分析失败')
  } finally {
    reanalyzing.value = false
  }
}

function navigate(imageId: string | null): void {
  if (imageId) void router.push(`/materials/packages/${route.params.packageId}/images/${imageId}`)
}

function requestLeave(path: string): boolean {
  if (!dirty.value) return true
  pendingPath.value = path
  leaveDialogOpen.value = true
  return false
}

async function saveAndLeave(): Promise<void> {
  await save()
  if (!dirty.value) continueLeave()
}

function discardAndLeave(): void {
  dirty.value = false
  continueLeave()
}

function continueLeave(): void {
  const path = pendingPath.value
  leaveDialogOpen.value = false
  pendingPath.value = null
  if (path) void router.push(path)
}

function stay(): void {
  leaveDialogOpen.value = false
  pendingPath.value = null
}

function resetFactsRuntime(): void {
  factsGeneration++
  stopFactsRequest()
  factsView.value = null
  draft.value = null
  dirty.value = false
}

function stopFactsRequest(): void {
  factsAbort?.abort()
  factsAbort = null
  clearPollTimer()
}

function clearPollTimer(): void {
  if (pollTimer !== null) window.clearTimeout(pollTimer)
  pollTimer = null
}

function analysisIsActive(view: VisualFactsView): boolean {
  return view.analysisStatus === 'PENDING' || view.analysisStatus === 'RUNNING'
}

function normalizeFacts(value: VisualFacts): VisualFacts {
  const lines = (values: string[]) => [...new Set(values.map((item) => item.trim()).filter(Boolean))]
  const scalar = (value: string | null | undefined) => value?.trim() ?? ''
  return {
    common: {
      categoryAppearance: scalar(value.common.categoryAppearance),
      dominantColors: lines(value.common.dominantColors),
      visibleMaterials: lines(value.common.visibleMaterials),
      shapes: lines(value.common.shapes),
      visibleComponents: lines(value.common.visibleComponents),
      patterns: lines(value.common.patterns),
      visibleText: lines(value.common.visibleText),
      background: scalar(value.common.background),
      viewTypes: lines(value.common.viewTypes)
    },
    attributes: value.attributes
      .map(({ name, value }) => ({ name: name.trim(), value: value.trim() }))
      .filter(({ name, value }) => name.length > 0 || value.length > 0),
    limitations: lines(value.limitations),
    conflicts: lines(value.conflicts)
  }
}

function updateLines(target: string[], event: Event): void {
  target.splice(0, target.length, ...(event.target as HTMLTextAreaElement).value.split('\n'))
  markDirty()
}

function addAttribute(): void {
  draft.value?.attributes.push({ name: '', value: '' })
  markDirty()
}

function removeAttribute(index: number): void {
  draft.value?.attributes.splice(index, 1)
  markDirty()
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback
}

function warnBeforeUnload(event: BeforeUnloadEvent): void {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave((to) => requestLeave(to.fullPath))
onBeforeRouteUpdate((to) => requestLeave(to.fullPath))
onMounted(() => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  void load()
})
onBeforeUnmount(() => {
  detailGeneration++
  detailAbort?.abort()
  detailAbort = null
  resetFactsRuntime()
  window.removeEventListener('beforeunload', warnBeforeUnload)
})
watch(() => route.params.imageId, () => { void load() })
</script>

<template>
  <section class="grid h-full min-h-0 grid-cols-[minmax(0,1fr)_420px] bg-bg-page max-lg:grid-cols-[minmax(0,3fr)_minmax(320px,2fr)] max-sm:block max-sm:overflow-y-auto">
    <div class="flex min-h-0 flex-col border-r border-border p-4">
      <div
        v-if="loading"
        class="m-auto text-sm text-fg-muted"
      >
        正在加载...
      </div>
      <template v-else-if="detail">
        <div class="min-h-0 flex-1 bg-bg-sunken">
          <img
            :src="detail.image.previewUrl ?? ''"
            :alt="detail.image.displayName"
            class="h-full w-full object-contain"
          >
        </div>
        <div class="mt-3 flex items-center justify-between">
          <AppButton
            variant="ghost"
            :disabled="!detail.previousImageId"
            @click="navigate(detail.previousImageId)"
          >
            上一张
          </AppButton>
          <span class="truncate px-3 text-sm text-fg-primary">{{ detail.image.displayName }}</span>
          <AppButton
            variant="ghost"
            :disabled="!detail.nextImageId"
            @click="navigate(detail.nextImageId)"
          >
            下一张
          </AppButton>
        </div>
      </template>
    </div>

    <aside class="overflow-y-auto p-5">
      <h1 class="text-base font-semibold text-fg-primary">
        商品视觉分析
      </h1>
      <p class="mt-1 text-sm text-fg-muted">
        这是同商品的综合视觉分析，基于该商品中最多 2 张素材图片生成。
      </p>

      <div
        v-if="error && !factsView"
        class="mt-4 text-sm text-danger"
      >
        <p>{{ error }}</p>
        <AppButton
          v-if="skuId"
          class="mt-2"
          size="sm"
          variant="ghost"
          @click="loadFacts(skuId)"
        >
          重试加载
        </AppButton>
      </div>

      <div
        v-if="analysisActive"
        class="mt-4 text-sm text-fg-muted"
      >
        正在分析商品图片
      </div>
      <template v-if="skuId && factsView && draft">
        <fieldset
          :disabled="!canEdit"
          class="mt-4 space-y-3 text-sm disabled:opacity-70"
        >
          <label class="block">类别外观<input
            v-model="draft.common.categoryAppearance"
            class="field"
            @input="markDirty"
          ></label>
          <label class="block">背景<input
            v-model="draft.common.background"
            class="field"
            @input="markDirty"
          ></label>
          <label
            v-for="field in ([
              ['dominantColors', '主色'], ['visibleMaterials', '可见材质'], ['shapes', '形状'],
              ['visibleComponents', '可见组件'], ['patterns', '图案'], ['visibleText', '可见文字'],
              ['viewTypes', '视角']
            ] as const)"
            :key="field[0]"
            class="block"
          >
            {{ field[1] }}（每行一项）
            <textarea
              :value="draft.common[field[0]].join('\n')"
              class="field min-h-20"
              @input="updateLines(draft.common[field[0]], $event)"
            />
          </label>
          <label class="block">限制说明（每行一项）<textarea
            :value="draft.limitations.join('\n')"
            class="field min-h-20"
            @input="updateLines(draft.limitations, $event)"
          /></label>
          <label class="block">事实冲突（每行一项）<textarea
            :value="draft.conflicts.join('\n')"
            class="field min-h-20"
            @input="updateLines(draft.conflicts, $event)"
          /></label>

          <div>
            <div class="mb-2 flex items-center justify-between">
              <span>类别属性</span>
              <AppButton
                size="sm"
                variant="ghost"
                @click="addAttribute"
              >
                添加属性
              </AppButton>
            </div>
            <div
              v-for="(attribute, index) in draft.attributes"
              :key="index"
              class="mb-2 grid grid-cols-[1fr_1fr_auto] gap-2"
            >
              <input
                v-model="attribute.name"
                aria-label="属性名"
                class="field mt-0"
                @input="markDirty"
              >
              <input
                v-model="attribute.value"
                aria-label="属性值"
                class="field mt-0"
                @input="markDirty"
              >
              <AppButton
                size="sm"
                variant="ghost"
                aria-label="删除属性"
                @click="removeAttribute(index)"
              >
                删除
              </AppButton>
            </div>
          </div>
        </fieldset>

        <p
          v-if="factsView.analysisStatus === 'FAILED'"
          class="mt-3 text-sm text-danger"
        >
          分析未完成，可保留当前事实并重新分析。
        </p>
        <p
          v-if="error"
          class="mt-3 text-sm text-danger"
        >
          {{ error }}
        </p>
        <div
          v-if="conflict"
          class="mt-2"
        >
          <AppButton
            size="sm"
            variant="ghost"
            @click="loadFacts(factsView.skuId)"
          >
            加载最新版本
          </AppButton>
        </div>
        <div class="sticky bottom-0 mt-4 flex flex-wrap gap-2 bg-bg-page py-3">
          <AppButton
            :disabled="!dirty || saving || analysisActive"
            :loading="saving"
            @click="save"
          >
            保存事实
          </AppButton>
          <AppButton
            variant="ghost"
            :disabled="!dirty || saving"
            @click="cancelChanges"
          >
            取消修改
          </AppButton>
          <AppButton
            variant="ghost"
            :disabled="dirty || reanalyzing || analysisActive"
            :loading="reanalyzing"
            @click="reanalyze"
          >
            重新分析
          </AppButton>
        </div>
        <p class="text-xs text-fg-muted">
          状态：{{ factsView.analysisStatus }} · 版本 {{ factsView.version }}
          <template v-if="factsView.writer">
            · {{ factsView.writer === 'AI_GENERATED' ? 'AI 生成' : '人工编辑' }}
          </template>
        </p>
      </template>
      <p
        v-else-if="!skuId"
        class="mt-6 text-sm text-fg-muted"
      >
        当前素材没有关联 SKU，暂时无法生成商品视觉事实。
      </p>
    </aside>

    <AppDialog
      :open="leaveDialogOpen"
      title="保存视觉事实修改？"
      :close-on-overlay="false"
      @update:open="(open) => { if (!open) stay() }"
    >
      <template #body>
        <p class="text-sm text-fg-secondary">
          离开后，本次未保存的修改不会保留。
        </p>
      </template>
      <template #footer>
        <AppButton
          variant="ghost"
          @click="stay"
        >
          继续编辑
        </AppButton>
        <AppButton
          variant="ghost"
          @click="discardAndLeave"
        >
          放弃并离开
        </AppButton>
        <AppButton
          :loading="saving"
          @click="saveAndLeave"
        >
          保存并离开
        </AppButton>
      </template>
    </AppDialog>
  </section>
</template>

<style scoped>
.field { @apply mt-1 block w-full rounded border border-border bg-bg-panel px-2 py-1.5 text-fg-primary; }
</style>
