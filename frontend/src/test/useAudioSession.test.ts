import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAudioSession } from '../hooks/useAudioSession'

const vadMock = vi.hoisted(() => ({ onSpeechEnd: null as ((audio: Float32Array) => void) | null }))
vi.mock('../hooks/useVad', () => ({
  useVad: (onSpeechEnd: (audio: Float32Array) => void) => {
    vadMock.onSpeechEnd = onSpeechEnd
    return { start: async () => {}, pause: vi.fn(), resume: vi.fn(), stop: vi.fn(), error: '' }
  },
}))

class FakeWebSocket {
  static OPEN = 1
  static CONNECTING = 0
  static last: FakeWebSocket
  readyState = FakeWebSocket.CONNECTING
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  sent: string[] = []
  constructor() { FakeWebSocket.last = this }
  open() { this.readyState = FakeWebSocket.OPEN; this.onopen?.(new Event('open')) }
  send(value: string) { this.sent.push(value) }
  closeFromServer() { this.readyState = 3; this.onclose?.({} as CloseEvent) }
  close() { this.readyState = 3; this.onclose?.({} as CloseEvent) }
}

describe('useAudioSession close lifecycle', () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it('finishes visibly if the socket closes before SESSION_CLOSED arrives', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { result } = renderHook(() => useAudioSession())
    await act(async () => { await result.current.start() })
    act(() => FakeWebSocket.last.open())
    act(() => result.current.end())
    act(() => FakeWebSocket.last.closeFromServer())
    expect(result.current.state).toBe('closed')
    expect(result.current.notice).toContain('ngắt trước')
  })

  it('times out a missing close acknowledgement', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { result } = renderHook(() => useAudioSession())
    await act(async () => { await result.current.start() })
    act(() => FakeWebSocket.last.open())
    act(() => result.current.end())
    expect(result.current.state).toBe('closing')
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(result.current.state).toBe('closed')
    expect(result.current.notice).toContain('chưa xác nhận')
  })

  it('drops sub-200ms audio and splits long speech into ordered, bounded chunks', async () => {
    vi.stubGlobal('WebSocket', FakeWebSocket)
    const { result } = renderHook(() => useAudioSession())
    await act(async () => { await result.current.start() })
    act(() => FakeWebSocket.last.open())
    act(() => {
      vadMock.onSpeechEnd?.(new Float32Array(3_199))
      vadMock.onSpeechEnd?.(new Float32Array(31 * 16_000))
    })
    const frames = FakeWebSocket.last.sent.map((value) => JSON.parse(value)).filter((value) => value.type === 'AUDIO_CHUNK')
    expect(frames).toHaveLength(11)
    expect(frames.map((frame) => frame.sequenceNumber)).toEqual(Array.from({ length: 11 }, (_, i) => i))
    expect(frames.every((frame) => atob(frame.data).length <= 96_000)).toBe(true)
    expect(Date.parse(frames[1].capturedAt) - Date.parse(frames[0].capturedAt)).toBeGreaterThan(2_700)
  })
})
