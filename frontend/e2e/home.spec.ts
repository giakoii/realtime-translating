import { expect, test } from '@playwright/test'

test('starts a fake microphone session and exercises controls and summary UI', async ({ page }) => {
  test.setTimeout(120_000)
  const sent: Array<Record<string, unknown>> = []
  let modelLoaded = false
  page.on('response', (response) => {
    if (response.url().includes('/vad/silero_vad.onnx')) modelLoaded = response.ok()
  })
  await page.routeWebSocket('**/ws/audio', (socket) => {
    socket.onMessage((raw) => {
      const frame = JSON.parse(String(raw)) as Record<string, unknown>
      sent.push(frame)
      if (frame.type === 'AUDIO_CHUNK') {
        expect(frame.sampleRate).toBe(16_000)
        expect(String(frame.data).length).toBeGreaterThan(100)
      }
      if (frame.type === 'AUDIO_CHUNK') socket.send(JSON.stringify({
        type: 'TRANSLATION', sessionId: frame.sessionId, sequenceNumber: frame.sequenceNumber,
        originalText: '今日は会議を始めましょう。', translatedText: 'Hôm nay chúng ta bắt đầu cuộc họp.',
        sourceLang: 'ja', targetLang: 'vi', timestamp: new Date().toISOString(),
      }))
      if (frame.type === 'MANUAL_SUMMARY') socket.send(JSON.stringify({
        type: 'SUMMARY', sessionId: frame.sessionId, triggerType: 'manual', messagesCount: 1,
        generatedAt: new Date().toISOString(), summary: { rawContent: '### Nhất trí\nGửi bản nháp.', actionItems: [{ who: 'An', what: 'Gửi bản nháp', deadline: null }] },
      }))
      if (frame.type === 'MANUAL_SUMMARY') socket.send(JSON.stringify({ type: 'TITLE', sessionId: frame.sessionId, title: 'Kế hoạch cuộc họp' }))
      if (frame.type === 'CLOSE_SESSION') socket.send(JSON.stringify({ type: 'SESSION_CLOSED', sessionId: frame.sessionId, totalMessages: 1 }))
    })
  })
  await page.context().grantPermissions(['microphone'])
  await page.goto('/')
  const hasFakeMicrophone = await page.evaluate(async () => {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    stream.getTracks().forEach((track) => track.stop())
    return stream.getAudioTracks().length > 0
  })
  expect(hasFakeMicrophone).toBe(true)
  await expect(page.getByRole('heading', { name: /Cuộc trò chuyện/ })).toBeVisible()
  await page.getByRole('button', { name: 'Bắt đầu phiên' }).click()
  await expect(page.getByText('Đang phiên dịch')).toBeVisible({ timeout: 20_000 })
  await expect.poll(() => modelLoaded, { timeout: 30_000 }).toBe(true)
  await expect.poll(() => sent.filter((frame) => frame.type === 'AUDIO_CHUNK').length, { timeout: 30_000 }).toBeGreaterThan(0)
  await expect(page.getByText('Hôm nay chúng ta bắt đầu cuộc họp.').first()).toBeVisible()
  await expect(page.getByRole('button', { name: 'Tạm dừng' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.getByRole('button', { name: 'Tóm tắt ngay' }).click()
  await expect(page.getByText('Gửi bản nháp', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Kế hoạch cuộc họp' })).toBeVisible()
  expect(sent.some((frame) => frame.type === 'MANUAL_SUMMARY')).toBe(true)
  await page.getByRole('button', { name: 'Kết thúc' }).click()
  await expect(page.getByText('Đã kết thúc', { exact: true })).toBeVisible()
  expect(sent.some((frame) => frame.type === 'CLOSE_SESSION')).toBe(true)
  await page.screenshot({ path: 'test-results/ui-review.png', fullPage: true })
})
