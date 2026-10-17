import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { resolve } from 'node:path'
const dest = resolve('public/vad')
mkdirSync(dest, { recursive: true })
const sources = [
  ['node_modules/@ricky0123/vad-web/dist/silero_vad.onnx', 'silero_vad.onnx'],
  ['node_modules/@ricky0123/vad-web/dist/vad.worklet.bundle.min.js', 'vad.worklet.bundle.min.js'],
]
for (const file of [
  'ort-wasm-simd-threaded.wasm',
  'ort-wasm-simd-threaded.mjs',
  'ort-wasm-simd-threaded.jsep.wasm',
  'ort-wasm-simd-threaded.jsep.mjs',
]) rmSync(resolve(dest, file), { force: true })
for (const [from, to] of sources) {
  if (!existsSync(from)) throw new Error(`Missing VAD runtime asset: ${from}`)
  cpSync(from, resolve(dest, to))
}
