import { useCallback, useEffect, useRef, useState } from 'react'
import type { ActionItem, ServerFrame } from '../contract'
import { audioFrame } from '../lib/audio'
import { reconnectDelay, SessionSocket } from '../lib/sessionSocket'
import { useVad } from './useVad'

export type SessionState = 'idle' | 'connecting' | 'live' | 'paused' | 'closing' | 'closed' | 'error'
export type Subtitle = Extract<ServerFrame, { type: 'TRANSLATION' }>
const url = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws/audio'

function newSessionId(): string {
  const d = new Date()
  const stamp = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}_${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}${String(d.getSeconds()).padStart(2, '0')}`
  return `sess_${stamp}_${Math.random().toString(36).slice(2, 8)}`
}

export function useAudioSession() {
  const [state, setState] = useState<SessionState>('idle')
  const [sessionId, setSessionId] = useState('')
  const [connected, setConnected] = useState(false)
  const [lines, setLines] = useState<Subtitle[]>([])
  const [title, setTitle] = useState('')
  const [summary, setSummary] = useState('')
  const [actionItems, setActionItems] = useState<ActionItem[]>([])
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')

  const socket = useRef<SessionSocket | null>(null)
  const session = useRef('')
  const sequence = useRef(0)
  const attempts = useRef(0)
  const retryTimer = useRef<number | undefined>(undefined)
  const closeTimer = useRef<number | undefined>(undefined)
  const intentional = useRef(false)
  const closeRequested = useRef(false)
  const live = useRef(false)
  const micReady = useRef(false)
  const socketReady = useRef(false)
  const paused = useRef(false)
  const generation = useRef(0)

  const sendAudio = useCallback((audio: Float32Array) => {
    if (live.current && socket.current?.readyState === WebSocket.OPEN) {
      const minSamples = 3_200 // Ignore segments shorter than 200 ms at 16 kHz.
      const maxSamplesPerFrame = 48_000 // 3 s / 96 KB PCM, safely under the JSON frame limit.
      if (audio.length < minSamples) return
      const chunkCount = Math.ceil(audio.length / maxSamplesPerFrame)
      const chunkSize = Math.ceil(audio.length / chunkCount)
      const capturedAt = Date.now() - (audio.length / 16_000) * 1000
      for (let offset = 0; offset < audio.length; offset += chunkSize) {
        const timestamp = new Date(capturedAt + (offset / 16_000) * 1000).toISOString()
        socket.current.send(audioFrame(session.current, sequence.current++, audio.subarray(offset, offset + chunkSize), timestamp))
      }
    }
  }, [])
  const mic = useVad(sendAudio)
  const stopMic = mic.stop
  const pauseMic = mic.pause
  const resumeMic = mic.resume

  const onMessage = useCallback((frame: ServerFrame) => {
    if (frame.type === 'TRANSLATION') setLines((current) => [...current, frame].slice(-80))
    else if (frame.type === 'SUMMARY') {
      setSummary(frame.summary.rawContent)
      setActionItems(frame.summary.actionItems ?? [])
      setNotice('Tóm tắt đã sẵn sàng.')
    } else if (frame.type === 'TITLE') setTitle(frame.title)
    else if (frame.type === 'SUMMARY_SKIPPED') {
      setNotice({
        NOT_ENOUGH_CONTENT: 'Chưa có đủ nội dung để tóm tắt.',
        NO_NEW_CONTENT: 'Chưa có nội dung mới kể từ lần tóm tắt trước.',
        IN_PROGRESS: 'Đang tạo tóm tắt, vui lòng chờ.',
      }[frame.reason])
    } else if (frame.type === 'ERROR') {
      setError(frame.message)
      setNotice(frame.message)
      if (frame.code === 'SESSION_ENDED' || frame.code === 'SESSION_BUSY') {
        intentional.current = true
        live.current = false
        micReady.current = false
        stopMic()
        setState('error')
        socket.current?.close()
      }
    } else if (frame.type === 'SESSION_CLOSED') {
      intentional.current = true
      closeRequested.current = false
      if (closeTimer.current !== undefined) window.clearTimeout(closeTimer.current)
      closeTimer.current = undefined
      live.current = false
      micReady.current = false
      stopMic()
      setState('closed')
      setNotice(`Đã kết thúc phiên (${frame.totalMessages} câu).`)
      socket.current?.close()
    }
  }, [stopMic])

  const connect = useCallback((fresh: boolean) => {
    if (retryTimer.current !== undefined) window.clearTimeout(retryTimer.current)
    retryTimer.current = undefined
    const ownGeneration = ++generation.current
    if (fresh) {
      session.current = newSessionId()
      setSessionId(session.current)
      sequence.current = 0
      setLines([])
      setTitle('')
      setSummary('')
      setActionItems([])
      setNotice('')
    }
    setState('connecting')
    setConnected(false)
    socketReady.current = false
    const currentSocket = new SessionSocket(url, {
      onOpen: () => {
        if (ownGeneration !== generation.current || intentional.current) {
          currentSocket.close()
          return
        }
        socketReady.current = true
        setConnected(true)
        if (micReady.current && !paused.current) {
          setState('live')
          live.current = true
        } else if (paused.current) {
          setState('paused')
          live.current = false
        }
      },
      onMessage: (frame) => {
        if (ownGeneration === generation.current) onMessage(frame)
      },
      onError: () => {
        if (ownGeneration === generation.current) setNotice('Đang gặp sự cố kết nối.')
      },
      onClose: () => {
        if (ownGeneration !== generation.current) return
        setConnected(false)
        if (closeRequested.current) {
          closeRequested.current = false
          if (closeTimer.current !== undefined) window.clearTimeout(closeTimer.current)
          closeTimer.current = undefined
          setState('closed')
          setNotice('Phiên đã ngắt trước khi máy chủ xác nhận kết thúc.')
          return
        }
        if (intentional.current) return
        live.current = false
        setNotice('Đã mất kết nối. Đang mở phiên mới…')
        const delay = reconnectDelay(attempts.current++)
        retryTimer.current = window.setTimeout(() => connect(true), delay)
      },
    })
    socket.current = currentSocket
  }, [onMessage])

  const start = useCallback(async () => {
    intentional.current = false
    paused.current = false
    micReady.current = false
    socketReady.current = false
    attempts.current = 0
    setError('')
    connect(true)
    try {
      await mic.start()
      micReady.current = true
      if (socketReady.current && !intentional.current) {
        if (paused.current) setState('paused')
        else { setState('live'); live.current = true }
      }
    } catch {
      intentional.current = true
      live.current = false
      micReady.current = false
      generation.current++
      socket.current?.close()
      setState('error')
      setError(mic.error || 'Không thể truy cập micro.')
    }
  }, [connect, mic])

  const pause = useCallback(() => {
    paused.current = true
    pauseMic()
    live.current = false
    setState('paused')
  }, [pauseMic])
  const resume = useCallback(() => {
    paused.current = false
    resumeMic()
    live.current = true
    setState('live')
  }, [resumeMic])
  const requestSummary = useCallback(() => {
    if (socket.current?.readyState === WebSocket.OPEN) {
      socket.current.send({ type: 'MANUAL_SUMMARY', sessionId: session.current })
      setNotice('Đang yêu cầu tóm tắt…')
    }
  }, [])
  const end = useCallback(() => {
    intentional.current = true
    live.current = false
    micReady.current = false
    paused.current = false
    closeRequested.current = false
    setState('closing')
    stopMic()
    if (retryTimer.current !== undefined) window.clearTimeout(retryTimer.current)
    retryTimer.current = undefined
    if (socket.current?.readyState === WebSocket.OPEN) {
      closeRequested.current = true
      socket.current.send({ type: 'CLOSE_SESSION', sessionId: session.current })
      closeTimer.current = window.setTimeout(() => {
        closeRequested.current = false
        socket.current?.close()
        setConnected(false)
        setState('closed')
        setNotice('Đã ngắt kết nối. Máy chủ chưa xác nhận lưu phiên.')
      }, 5000)
    } else {
      generation.current++
      socket.current?.close()
      setState('closed')
    }
  }, [stopMic])

  useEffect(() => () => {
    intentional.current = true
    generation.current++
    if (retryTimer.current !== undefined) window.clearTimeout(retryTimer.current)
    if (closeTimer.current !== undefined) window.clearTimeout(closeTimer.current)
    socket.current?.close()
  }, [])

  return { state, sessionId, connected, lines, title, summary, actionItems, notice, error, micError: mic.error, start, pause, resume, requestSummary, end }
}
