import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import type { ApiError } from '@/types/api'
import { newTraceId } from '@/utils/id'

/**
 * STOMP 通用连接 + 重连 + 重订阅。
 * 见 web.md §5.3。
 *
 *  - 重连退避 1 → 2 → 4 → 8 → 30s（封顶），自定义表
 *  - 重连成功后重放所有订阅
 *  - 不持久化 WS 订阅：刷新页面后从 Pinia 重建订阅集合
 */
export interface StompClientOptions {
  url: string
  headers?: Record<string, string>
  reconnectDelays?: number[]
  onConnect?: () => void
  onDisconnect?: () => void
  onError?: (error: ApiError) => void
}

export interface StompHandle<T = unknown> {
  destination: string
  handler: (msg: T) => void
  subscription: StompSubscription | null
}

const DEFAULT_RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 30_000]

export class StompConnection {
  private client: Client
  private handles: Map<string, StompHandle<unknown>> = new Map()
  private connected = false
  private currentDelay = 0
  private traceId = newTraceId()

  constructor(private readonly opts: StompClientOptions) {
    const delays = opts.reconnectDelays ?? DEFAULT_RECONNECT_DELAYS
    this.client = new Client({
      brokerURL: opts.url,
      connectHeaders: {
        'X-Auth-Token': '',
        'X-Trace-Id': this.traceId,
        ...(opts.headers ?? {})
      },
      reconnectDelay: 0, // 自定义退避表
      heartbeatIncoming: 30_000,
      heartbeatOutgoing: 30_000
    })
    this.client.onConnect = () => {
      this.connected = true
      this.currentDelay = 0
      // 重订阅
      for (const [dest, h] of this.handles) {
        h.subscription = this.client.subscribe(dest, (msg) => this.dispatch(msg, h))
      }
      this.opts.onConnect?.()
    }
    this.client.onDisconnect = () => {
      this.connected = false
      this.opts.onDisconnect?.()
    }
    this.client.onStompError = (frame) => {
      this.opts.onError?.({
        status: 0,
        errorCode: 'WS_RECONNECTING',
        message: `STOMP error: ${frame.headers['message'] ?? 'unknown'}`,
        traceId: this.traceId
      })
    }
    this.client.onWebSocketClose = () => {
      this.connected = false
      // 自定义退避：下一次 reconnect
      this.scheduleReconnect(delays)
    }
  }

  private reconnectTimer: number | null = null
  private scheduleReconnect(delays: number[]): void {
    if (this.reconnectTimer != null) return
    const delay = delays[Math.min(this.currentDelay, delays.length - 1)]
    this.currentDelay++
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      try { this.client.activate() } catch { /* ignore */ }
    }, delay)
  }

  private dispatch<T>(msg: IMessage, h: StompHandle<T>): void {
    try {
      const body = JSON.parse(msg.body) as T
      h.handler(body)
    } catch (e) {
      if (__DEV__) console.debug('[WS] dispatch parse error', e)
    }
  }

  activate(): void {
    try { this.client.activate() } catch { /* ignore */ }
  }

  deactivate(): void {
    if (this.reconnectTimer != null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    try { this.client.deactivate() } catch { /* ignore */ }
  }

  subscribe<T>(destination: string, handler: (msg: T) => void): { unsubscribe: () => void } {
    const handle: StompHandle<T> = { destination, handler, subscription: null }
    this.handles.set(destination, handle as StompHandle<unknown>)
    if (this.connected) {
      handle.subscription = this.client.subscribe(destination, (msg) => this.dispatch(msg, handle))
    }
    return {
      unsubscribe: () => {
        try { handle.subscription?.unsubscribe() } catch { /* ignore */ }
        this.handles.delete(destination)
      }
    }
  }

  unsubscribeAll(): void {
    for (const h of this.handles.values()) {
      try { h.subscription?.unsubscribe() } catch { /* ignore */ }
    }
    this.handles.clear()
  }

  isConnected(): boolean {
    return this.connected
  }
}

/** 单例（应用范围共享一条 STOMP 连接）。 */
let singleton: StompConnection | null = null

export function getStompConnection(opts: StompClientOptions): StompConnection {
  if (!singleton) {
    singleton = new StompConnection(opts)
    singleton.activate()
  }
  return singleton
}

export function disposeStompConnection(): void {
  if (singleton) {
    singleton.deactivate()
    singleton = null
  }
}