import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { SubtitleList } from '../components/SubtitleList'
import { SummaryPanel } from '../components/SummaryPanel'
describe('transcript and summary',()=>{it('shows empty transcript hint',()=>{render(<SubtitleList lines={[]}/>);expect(screen.getByText('Phụ đề sẽ hiện ở đây')).toBeTruthy()});it('renders summary and action items',()=>{render(<SummaryPanel title="Buổi họp" summary="### Kết quả" actionItems={[{who:'An',what:'Gửi bản nháp',deadline:'mai'}]} notice="Xong"/>);expect(screen.getByText('Buổi họp')).toBeTruthy();expect(screen.getByText('Gửi bản nháp')).toBeTruthy()})})
