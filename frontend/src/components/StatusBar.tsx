import type { SessionState } from '../hooks/useAudioSession'

const labels: Record<SessionState, string> = { idle: 'Sẵn sàng', connecting: 'Đang kết nối…', live: 'Đang phiên dịch', paused: 'Đã tạm dừng', closing: 'Đang kết thúc…', closed: 'Đã kết thúc', error: 'Cần chú ý' }
export function StatusBar({ state, sessionId, connected }: { state: SessionState; sessionId: string; connected: boolean }) {
  return <div className="status"><span className={`dot ${state}`} /><span>{labels[state]}</span><span className="connection">{connected ? 'Máy chủ đã kết nối' : state === 'live' ? 'Đang kết nối lại…' : 'Chưa kết nối'}</span>{sessionId && <code>{sessionId}</code>}</div>
}
