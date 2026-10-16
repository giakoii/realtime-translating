const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const WebSocket = require('../../frontend/node_modules/ws');

const postgresRoot = path.resolve(process.argv[2] || '');
const websocketUrl = process.argv[3] || 'ws://127.0.0.1:8081/ws/audio';
const verifyUrl = process.argv[4] || 'http://127.0.0.1:8082';
const tempRoot = path.resolve(os.tmpdir()) + path.sep;
if (!postgresRoot.toLowerCase().startsWith(tempRoot.toLowerCase())) {
  throw new Error('PostgresRoot must be an isolated directory under the system temp directory.');
}

const dataDirectory = path.join(postgresRoot, 'data');
const pgControl = path.join(postgresRoot, 'dist', 'bin', 'pg_ctl.exe');
const postgresLog = path.join(postgresRoot, 'postgres.log');
const port = Number(fs.readFileSync(path.join(postgresRoot, 'port.txt'), 'utf8').trim());
if (!fs.existsSync(path.join(dataDirectory, 'PG_VERSION')) || !fs.existsSync(pgControl) || !port) {
  throw new Error('PostgresRoot does not contain the expected isolated PostgreSQL cluster.');
}

let databaseStopped = false;
let socket;
const sessionId = `outage_${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`;
const audio = Buffer.alloc(6400).toString('base64');
const inbox = [];
const waiters = [];

function runPgControl(args) {
  const result = spawnSync(pgControl, args, { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`pg_ctl exited with ${result.status}`);
}

function connect() {
  return new Promise((resolve, reject) => {
    socket = new WebSocket(websocketUrl);
    socket.once('open', resolve);
    socket.once('error', reject);
    socket.on('message', raw => {
      const message = JSON.parse(raw.toString());
      const waiterIndex = waiters.findIndex(waiter => waiter.type === message.type || message.type === 'ERROR');
      if (waiterIndex >= 0) {
        const [waiter] = waiters.splice(waiterIndex, 1);
        clearTimeout(waiter.timeout);
        if (message.type === 'ERROR') waiter.reject(new Error(`${message.code}: ${message.message}`));
        else waiter.resolve(message);
      } else {
        inbox.push(message);
      }
    });
  });
}

function waitForType(type, timeoutMs = 20_000) {
  const existingIndex = inbox.findIndex(message => message.type === type || message.type === 'ERROR');
  if (existingIndex >= 0) {
    const [message] = inbox.splice(existingIndex, 1);
    return message.type === 'ERROR'
      ? Promise.reject(new Error(`${message.code}: ${message.message}`))
      : Promise.resolve(message);
  }
  return new Promise((resolve, reject) => {
    const waiter = { type, resolve, reject };
    waiter.timeout = setTimeout(() => {
      const index = waiters.indexOf(waiter);
      if (index >= 0) waiters.splice(index, 1);
      reject(new Error(`Timed out waiting for ${type}`));
    }, timeoutMs);
    waiters.push(waiter);
  });
}

function send(value) {
  socket.send(JSON.stringify(value));
}

async function main() {
  try {
    await connect();
    send({ type: 'AUDIO_CHUNK', sessionId, data: audio, sampleRate: 16000,
      sequenceNumber: 0, capturedAt: new Date().toISOString() });
    await waitForType('TRANSLATION');

    runPgControl(['-D', dataDirectory, '-m', 'fast', '-w', 'stop']);
    databaseStopped = true;

    for (let sequence = 1; sequence <= 3; sequence += 1) {
      send({ type: 'AUDIO_CHUNK', sessionId, data: audio, sampleRate: 16000,
        sequenceNumber: sequence, capturedAt: new Date(Date.now() + sequence * 1000).toISOString() });
    }
    for (let sequence = 1; sequence <= 3; sequence += 1) await waitForType('TRANSLATION');
    send({ type: 'CLOSE_SESSION', sessionId });
    await waitForType('SESSION_CLOSED', 30_000);

    runPgControl(['-D', dataDirectory, '-l', postgresLog, '-o', `-p ${port} -h 127.0.0.1`, '-w', 'start']);
    databaseStopped = false;

    const deadline = Date.now() + 30_000;
    let verification;
    while (Date.now() < deadline) {
      try {
        await fetch(`${verifyUrl}/retry`, { method: 'POST' });
        verification = await (await fetch(`${verifyUrl}/verify?sessionId=${sessionId}`)).json();
        if (verification.meetingCount === 1 && verification.messageCount === 4 &&
            verification.messagesOrdered && verification.endedAt) break;
      } catch (_) {
        verification = undefined;
      }
      await new Promise(resolve => setTimeout(resolve, 500));
    }

    if (!verification || verification.meetingCount !== 1 || verification.messageCount !== 4 ||
        !verification.messagesOrdered || !verification.endedAt) {
      throw new Error(`Persistence did not recover without loss or duplicates: ${JSON.stringify(verification)}`);
    }
    process.stdout.write(`${JSON.stringify({ sessionId, ...verification })}\n`);
  } finally {
    if (databaseStopped) {
      runPgControl(['-D', dataDirectory, '-l', postgresLog, '-o', `-p ${port} -h 127.0.0.1`, '-w', 'start']);
    }
    if (socket) socket.close();
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
