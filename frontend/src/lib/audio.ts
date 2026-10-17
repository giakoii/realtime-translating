import type { ClientFrame } from '../contract'

export function encodePcm16Le(samples: Float32Array): Uint8Array {
  const bytes = new Uint8Array(samples.length * 2)
  const view = new DataView(bytes.buffer)
  for (let i = 0; i < samples.length; i++) {
    const sample = Math.max(-1, Math.min(1, samples[i] ?? 0))
    view.setInt16(i * 2, sample < 0 ? Math.round(sample * 32768) : Math.round(sample * 32767), true)
  }
  return bytes
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i += 8192) {
    binary += String.fromCharCode(...bytes.subarray(i, Math.min(i + 8192, bytes.length)))
  }
  return btoa(binary)
}

export function audioFrame(sessionId: string, sequenceNumber: number, samples: Float32Array, capturedAt = new Date().toISOString()): ClientFrame {
  return { type: 'AUDIO_CHUNK', sessionId, sequenceNumber, sampleRate: 16000, capturedAt, data: bytesToBase64(encodePcm16Le(samples)) }
}
