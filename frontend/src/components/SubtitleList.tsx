import { useLayoutEffect, useRef } from 'react'
import type { Subtitle } from '../hooks/useAudioSession'

export function SubtitleList({ lines }: { lines: Subtitle[] }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const latestSequence = lines[lines.length - 1]?.sequenceNumber

  useLayoutEffect(() => {
    const container = containerRef.current
    if (container) container.scrollTop = container.scrollHeight
  }, [latestSequence])

  if (!lines.length) return <div className="empty"><span className="wave">〰</span><h2>Phụ đề sẽ hiện ở đây</h2><p>Bắt đầu phiên và nói tự nhiên. Câu mới nhất sẽ được làm nổi bật.</p></div>

  return (
    <div ref={containerRef} className="subtitles" aria-live="polite">
      {lines.map((line, i) => (
        <article key={`${line.sequenceNumber}-${i}`} className={`subtitle ${i === lines.length - 1 ? 'newest' : ''}`}>
          <div className="line-meta"><span>{line.sourceLang.toUpperCase()} → {line.targetLang.toUpperCase()}</span><time>{new Date(line.timestamp).toLocaleTimeString()}</time></div>
          <p>{line.originalText}</p>
          <p className="translated">{line.translatedText || 'Chưa có bản dịch cho câu này.'}</p>
        </article>
      ))}
    </div>
  )
}
