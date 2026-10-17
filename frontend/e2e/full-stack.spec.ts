import { expect, test } from '@playwright/test'

const backendUrl = process.env.E2E_BACKEND_WS_URL
const verifyUrl = process.env.E2E_VERIFY_URL

test('speech reaches the backend, produces subtitles and summary, then persists on end', async ({ page }) => {
  test.setTimeout(120_000)
  test.skip(!backendUrl || !verifyUrl, 'Set E2E_BACKEND_WS_URL and E2E_VERIFY_URL after starting FullStackTestServer.')
  const vadAssets = new Set<string>()
  page.on('response', (response) => { if (response.url().includes('/vad/')) vadAssets.add(new URL(response.url()).pathname) })
  await page.context().grantPermissions(['microphone'])
  await page.goto('/')
  await page.getByRole('button', { name: 'Bắt đầu phiên' }).click()
  await expect(page.getByText('Đang phiên dịch')).toBeVisible({ timeout: 30_000 })
  await expect.poll(async () => page.locator('article.subtitle').count(), { timeout: 60_000 }).toBeGreaterThanOrEqual(3)
  await expect.poll(() => page.locator('.subtitles').evaluate((container) => {
    const newest = container.querySelector('.subtitle.newest')
    if (!newest) return false
    const innerTop = container.getBoundingClientRect().top + container.clientTop
    const innerBottom = innerTop + container.clientHeight
    const newestBounds = newest.getBoundingClientRect()
    return newestBounds.top >= innerTop && newestBounds.bottom <= innerBottom
  })).toBe(true)
  expect(vadAssets.has('/vad/silero_vad.onnx')).toBe(true)

  const sessionId = await page.locator('.status code').innerText()
  await page.getByRole('button', { name: 'Tóm tắt ngay' }).click()
  await expect(page.getByText('Tóm tắt đã sẵn sàng.')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByRole('heading', { name: 'Cuộc họp kiểm thử', exact: true })).toBeVisible({ timeout: 30_000 })
  if (process.env.UI_REVIEW_SCREENSHOT) await page.screenshot({ path: process.env.UI_REVIEW_SCREENSHOT, fullPage: true })
  await page.getByRole('button', { name: 'Kết thúc' }).click()
  await expect(page.getByText(/Đã kết thúc phiên/)).toBeVisible({ timeout: 15_000 })

  const response = await page.request.get(`${verifyUrl}?sessionId=${encodeURIComponent(sessionId)}`)
  expect(response.ok()).toBe(true)
  const result = await response.json() as { meetingCount: number; messageCount: number; summaryCount: number; title: string; endedAt: string | null; messagesOrdered: boolean }
  expect(result.meetingCount).toBe(1)
  expect(result.messageCount).toBeGreaterThanOrEqual(3)
  expect(result.summaryCount).toBeGreaterThanOrEqual(1)
  expect(result.title).toBe('Cuộc họp kiểm thử')
  expect(result.endedAt).toBeTruthy()
  expect(result.messagesOrdered).toBe(true)
})
