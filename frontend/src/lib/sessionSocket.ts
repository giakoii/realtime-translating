import type { ClientFrame, ServerFrame } from '../contract'
import { isServerFrame } from '../contract'

export type SocketCallbacks = { onMessage: (frame: ServerFrame) => void; onClose: (event: CloseEvent) => void; onOpen?: () => void; onError?: () => void }
export class SessionSocket {
  private ws: WebSocket
  private queue: ClientFrame[] = []
  constructor(url: string, callbacks: SocketCallbacks, factory: (url: string) => WebSocket = (u) => new WebSocket(u)) {
    this.ws = factory(url)
    this.ws.onopen = () => { callbacks.onOpen?.(); for (const item of this.queue.splice(0)) this.send(item) }
    this.ws.onmessage = (event) => { try { const frame: unknown = JSON.parse(String(event.data)); if (isServerFrame(frame)) callbacks.onMessage(frame) } catch { /* Ignore malformed server frames. */ } }
    this.ws.onclose = callbacks.onClose
    this.ws.onerror = () => callbacks.onError?.()
  }
  send(frame: ClientFrame): boolean {
    if (this.ws.readyState === WebSocket.OPEN) { this.ws.send(JSON.stringify(frame)); return true }
    if (this.ws.readyState === WebSocket.CONNECTING && frame.type === 'AUDIO_CHUNK' && this.queue.length < 10) this.queue.push(frame)
    return false
  }
  close(): void { this.queue = []; this.ws.close() }
  get readyState(): number { return this.ws.readyState }
}

export function reconnectDelay(attempt: number): number { return Math.min(1000 * 2 ** Math.max(0, attempt), 10000) }
