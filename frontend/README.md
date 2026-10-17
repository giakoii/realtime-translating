# LinguaLive frontend

Web client for the real-time Japanese-to-Vietnamese translation session. It uses one `/ws/audio` WebSocket per meeting; audio is mono PCM16 little-endian at 16 kHz. The client generates a new session ID and resets the chunk sequence when it reconnects because the backend treats a disconnect as the end of the current meeting.

## Local development

Use Node.js 20 or later. Copy `.env.example` to `.env.local`, then set `VITE_WS_URL` to the backend WebSocket URL. The default is `ws://localhost:8080/ws/audio`.

```sh
npm ci
npm run dev
```

Allow microphone access when the browser asks. Chromium and Edge are supported. Production sites served over HTTPS must use a secure `wss://` URL.

`postinstall` and `build` copy the Silero model and VAD audio worklet from the pinned npm package into `public/vad/`. Vite bundles the ONNX Runtime loader and WASM from the pinned dependency as local hashed assets. Runtime files are served locally; the browser does not fetch them from a CDN. The app renders with system fonts and makes no external font request.

## Build and tests

```sh
npm run build
npm test
npm run e2e
```

Vitest covers PCM16LE encoding (including a 96 KB input), socket buffering/backoff, session close/reconnect behavior, subtitle and summary components. The regular Playwright test launches Chromium with a generated Japanese speech WAV as a fake microphone, loads local VAD assets, and exercises session controls with a mocked WebSocket.

For the real backend flow, set `TEST_DATABASE_URL` to an isolated PostgreSQL database and start the repository's test harness from `backend/`. It compiles the test support classes, starts `FullStackTestServer`, and serves WebSocket frames at `ws://127.0.0.1:8081/ws/audio` plus verification data at `http://127.0.0.1:8082/verify`.

```powershell
$env:TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/realtime_translator_test'
$env:TEST_DATABASE_USERNAME = 'postgres'
$env:TEST_DATABASE_PASSWORD = 'postgres'
.\scripts\start-full-stack-test-server.ps1
```

Then run from `frontend/`:

```sh
E2E_BACKEND_WS_URL=ws://127.0.0.1:8081/ws/audio E2E_VERIFY_URL=http://127.0.0.1:8082/verify npm run e2e:full
```

The full-stack Playwright case feeds the same synthetic speech to the fake microphone, waits for at least three translations, requests a summary, ends the session, and checks message ordering, summary, title and meeting closure through the verifier endpoint. Start the backend test server before this command; it is not started by Vite.

In PowerShell, set `$env:E2E_BACKEND_WS_URL` and `$env:E2E_VERIFY_URL` to those URLs, then run `npm run e2e:full`.
