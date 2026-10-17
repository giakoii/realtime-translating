import { useCallback, useEffect, useRef, useState } from 'react'
import { MicVAD } from '@ricky0123/vad-web'

const ortModuleUrl = new URL('../../node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.jsep.mjs', import.meta.url).href
const ortWasmUrl = new URL('../../node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.jsep.wasm', import.meta.url).href

export function useVad(onSpeechEnd: (audio: Float32Array) => void) {
  const cb = useRef(onSpeechEnd); cb.current = onSpeechEnd
  const vad = useRef<Awaited<ReturnType<typeof MicVAD.new>> | null>(null)
  const generation = useRef(0)
  const [error, setError] = useState('')
  const start = useCallback(async () => {
    setError('')
    const ownGeneration = ++generation.current
    try {
      const instance = await MicVAD.new({
        onSpeechEnd: (audio) => cb.current(audio),
        modelURL: '/vad/silero_vad.onnx',
        workletURL: '/vad/vad.worklet.bundle.min.js',
        ortConfig: (runtime) => {
          runtime.env.wasm.wasmPaths = { mjs: ortModuleUrl, wasm: ortWasmUrl }
        },
      })
      if (ownGeneration !== generation.current) { instance.destroy(); return }
      vad.current = instance
      instance.start()
    } catch (e) {
      if (ownGeneration !== generation.current) return
      const name = e instanceof Error ? e.name : ''
      setError(name === 'NotAllowedError' ? 'Đã chặn quyền dùng micro. Hãy cho phép micro trong trình duyệt.' : name === 'NotFoundError' ? 'Không tìm thấy micro trên thiết bị.' : `Không thể khởi động micro: ${e instanceof Error ? e.message : 'lỗi không xác định'}`)
      throw e
    }
  }, [])
  const pause = useCallback(() => vad.current?.pause(), [])
  const resume = useCallback(() => vad.current?.start(), [])
  const stop = useCallback(() => { generation.current++; vad.current?.destroy(); vad.current = null }, [])
  useEffect(() => () => { generation.current++; vad.current?.destroy() }, [])
  return { start, pause, resume, stop, error }
}
