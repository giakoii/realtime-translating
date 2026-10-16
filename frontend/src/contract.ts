export type ClientFrame =
  | { type: 'AUDIO_CHUNK'; sessionId: string; data: string; sampleRate: 16000; sequenceNumber: number; capturedAt: string }
  | { type: 'MANUAL_SUMMARY'; sessionId: string }
  | { type: 'CLOSE_SESSION'; sessionId: string }

export type ActionItem = { who: string | null; what: string; deadline: string | null }
export type ServerFrame =
  | { type: 'CHUNK_RECEIVED'; sequenceNumber: number; status: 'processing' | 'duplicate' }
  | { type: 'TRANSLATION'; sessionId: string; sequenceNumber: number; originalText: string; translatedText: string | null; sourceLang: string; targetLang: string; timestamp: string }
  | { type: 'SUMMARY'; sessionId: string; triggerType: 'auto_silence' | 'manual'; messagesCount: number; generatedAt: string; summary: { rawContent: string; actionItems: ActionItem[] | null } }
  | { type: 'TITLE'; sessionId: string; title: string }
  | { type: 'SUMMARY_SKIPPED'; reason: 'NOT_ENOUGH_CONTENT' | 'NO_NEW_CONTENT' | 'IN_PROGRESS' }
  | { type: 'SESSION_CLOSED'; sessionId: string; totalMessages: number }
  | { type: 'ERROR'; code: string; message: string; retryable: boolean; sequenceNumber?: number }

export function isServerFrame(value: unknown): value is ServerFrame {
  return !!value && typeof value === 'object' && typeof (value as { type?: unknown }).type === 'string'
    && ['CHUNK_RECEIVED','TRANSLATION','SUMMARY','TITLE','SUMMARY_SKIPPED','SESSION_CLOSED','ERROR'].includes((value as {type:string}).type)
}
