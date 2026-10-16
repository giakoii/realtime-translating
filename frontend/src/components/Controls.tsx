import type { SessionState } from '../hooks/useAudioSession'
export function Controls({ state, onStart, onPause, onResume, onSummary, onEnd }: { state: SessionState; onStart: () => void; onPause: () => void; onResume: () => void; onSummary: () => void; onEnd: () => void }) {
  const active = state === 'live' || state === 'paused'
  const canEnd = active || state === 'connecting'
  return <div className="controls">
    {state === 'idle' || state === 'closed' || state === 'error' ? <button className="primary" onClick={onStart}>Bắt đầu phiên</button> : null}
    {state === 'live' && <button onClick={onPause}>Tạm dừng</button>}{state === 'paused' && <button onClick={onResume}>Tiếp tục</button>}
    <button onClick={onSummary} disabled={!active}>Tóm tắt ngay</button><button className="danger" onClick={onEnd} disabled={!canEnd}>Kết thúc</button>
  </div>
}
