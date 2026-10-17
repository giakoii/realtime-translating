import ReactMarkdown from 'react-markdown'
import type { ActionItem } from '../contract'
export function SummaryPanel({ title, summary, actionItems, notice }: { title: string; summary: string; actionItems: ActionItem[]; notice: string }) {
  if (!title && !summary && !notice) return <aside className="summary-panel"><h2>Tóm tắt cuộc họp</h2><p className="muted">Khi có đủ nội dung, bạn có thể tạo tóm tắt bất cứ lúc nào.</p></aside>
  return <aside className="summary-panel"><h2>{title || 'Tóm tắt cuộc họp'}</h2>{notice && <p className="notice" role="status">{notice}</p>}{summary && <div className="markdown"><ReactMarkdown>{summary}</ReactMarkdown></div>}{actionItems.length > 0 && <><h3>Việc cần làm</h3><ul className="actions">{actionItems.map((item, i) => <li key={i}><strong>{item.what}</strong>{item.who && <span> — {item.who}</span>}{item.deadline && <small> · Hạn: {item.deadline}</small>}</li>)}</ul></>}</aside>
}
