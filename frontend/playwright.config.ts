import { resolve } from 'node:path'
import { defineConfig, devices } from '@playwright/test'
export default defineConfig({ testDir:'./e2e', use:{...devices['Desktop Chrome'],baseURL:'http://127.0.0.1:5173',launchOptions:{args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream',`--use-file-for-fake-audio-capture=${resolve('e2e/fixtures/speech-ja.wav')}`]}}, webServer:{command:'npm run dev -- --host 127.0.0.1',url:'http://127.0.0.1:5173',reuseExistingServer:!process.env.CI,env:{VITE_WS_URL:process.env.E2E_BACKEND_WS_URL||'ws://127.0.0.1:8080/ws/audio'}} })
