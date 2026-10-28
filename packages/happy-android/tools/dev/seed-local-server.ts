/**
 * Seeds a local Happy server with a throwaway account, a computer and a set
 * of sessions, then keeps them present (machine-alive, session-alive) until
 * stopped, so the Android and iOS apps can be compared screen by screen on
 * the same data without a real account or a real agent.
 *
 * Run from packages/happy-cli, against `pnpm --filter happy-server standalone:dev`:
 *
 *     npx tsx ../happy-android/tools/dev/seed-local-server.ts --server http://localhost:3005 [--secret <base64url>] [--offline]
 *
 * It prints the account's secret key; sign in on a device with "Restore with
 * secret key". Pass the same --secret again to reuse the account. --offline
 * seeds and exits without keeping anything present, which leaves the computer
 * offline. Everything it writes is fiction: no credentials leave this machine.
 */
import { randomUUID } from 'node:crypto'
import axios from 'axios'
import tweetnacl from 'tweetnacl'
import { io, type Socket } from 'socket.io-client'
import {
    authChallenge,
    decodeBase64,
    encodeBase64,
    encodeBase64Url,
    encryptWithDataKey,
    getRandomBytes,
    libsodiumEncryptForPublicKey,
    libsodiumPublicKeyFromSecretKey,
} from '../../../happy-cli/src/api/encryption'
import { deriveKey } from '../../../happy-cli/src/utils/deriveKey'

const args = process.argv.slice(2)
const option = (name: string) => {
    const index = args.indexOf(`--${name}`)
    return index >= 0 ? args[index + 1] : undefined
}
const server = option('server') ?? 'http://localhost:3005'
const offline = args.includes('--offline')
const secret = option('secret') ? decodeBase64(option('secret')!, 'base64url') : getRandomBytes(32)
const client = { 'X-Happy-Client': 'cli/seed-local-server' }

type SessionSeed = {
    tag: string
    summary: string
    path: string
    flavor?: string
    archived?: boolean
    /** Minutes ago the session last did anything. */
    minutesAgo: number
}

const home = '/Users/sam'
const machineId = 'seed-macbook'
const sessions: SessionSeed[] = [
    { tag: 'fix-login', summary: 'Fix the login redirect loop', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 3 },
    { tag: 'dark-mode', summary: 'Add dark mode to settings', path: `${home}/code/happy/.dev/worktree/dark-mode`, flavor: 'claude', minutesAgo: 12 },
    { tag: 'api-docs', summary: 'Write API docs for sessions', path: `${home}/code/happy-server`, flavor: 'codex', minutesAgo: 47 },
    { tag: 'flaky-tests', summary: 'Investigate flaky sync tests', path: `${home}/code/happy/.dev/worktree/flaky-tests`, flavor: 'claude', minutesAgo: 95 },
    { tag: 'landing', summary: 'Landing page copy pass', path: `${home}/code/website`, flavor: 'claude', minutesAgo: 60 * 5 },
    { tag: 'old-migration', summary: 'Migrate settings to v2', path: `${home}/code/happy`, flavor: 'claude', archived: true, minutesAgo: 60 * 26 },
    { tag: 'old-icons', summary: 'Regenerate the icon font', path: `${home}/code/happy`, flavor: 'codex', archived: true, minutesAgo: 60 * 24 * 3 },
]

async function main() {
    const { challenge, publicKey, signature } = authChallenge(secret)
    const auth = await axios.post(`${server}/v1/auth`, {
        challenge: encodeBase64(challenge),
        publicKey: encodeBase64(publicKey),
        signature: encodeBase64(signature),
    }, { headers: client })
    const token: string = auth.data.token
    const headers = { ...client, Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }

    // The account's content key: records carry a data key sealed to it, as
    // the app and the CLI write them.
    const contentPublicKey = libsodiumPublicKeyFromSecretKey(await deriveKey(secret, 'Happy EnCoder', ['content']))
    const seal = (key: Uint8Array) => {
        const sealed = libsodiumEncryptForPublicKey(key, contentPublicKey)
        const wrapped = new Uint8Array(sealed.length + 1)
        wrapped.set([0], 0)
        wrapped.set(sealed, 1)
        return encodeBase64(wrapped)
    }

    const machineKey = getRandomBytes(32)
    await axios.post(`${server}/v1/machines`, {
        id: machineId,
        metadata: encodeBase64(encryptWithDataKey({
            host: 'MacBook Pro',
            platform: 'darwin',
            happyCliVersion: '1.0.0',
            homeDir: home,
            happyHomeDir: `${home}/.happy`,
            happyLibDir: `${home}/.happy/lib`,
            cliAvailability: { claude: true, codex: true, gemini: false, openclaw: false, agy: false, detectedAt: Date.now() },
        }, machineKey)),
        daemonState: encodeBase64(encryptWithDataKey({ status: 'running', pid: 4242, startedAt: Date.now() }, machineKey)),
        dataEncryptionKey: seal(machineKey),
    }, { headers })

    const created: { id: string; seed: SessionSeed }[] = []
    for (const seed of sessions) {
        const key = getRandomBytes(32)
        const response = await axios.post(`${server}/v1/sessions`, {
            tag: seed.tag,
            metadata: encodeBase64(encryptWithDataKey({
                path: seed.path,
                host: 'MacBook Pro',
                homeDir: home,
                happyHomeDir: `${home}/.happy`,
                happyLibDir: `${home}/.happy/lib`,
                machineId,
                flavor: seed.flavor,
                summary: { text: seed.summary, updatedAt: Date.now() - seed.minutesAgo * 60_000 },
            }, key)),
            agentState: null,
            dataEncryptionKey: seal(key),
        }, { headers })
        const id: string = response.data.session.id
        created.push({ id, seed })
        if (seed.archived) {
            await axios.post(`${server}/v1/sessions/${id}/archive`, {}, { headers })
        }
    }

    console.log(JSON.stringify({
        server,
        secret: encodeBase64Url(secret),
        machineId,
        sessions: created.map(({ id, seed }) => ({ id, tag: seed.tag, archived: !!seed.archived })),
    }, null, 2))

    if (offline) return

    // Presence: the computer and its live sessions stay online while this runs.
    const ws = server.replace(/^http/, 'ws')
    const machine: Socket = io(ws, {
        transports: ['websocket'],
        path: '/v1/updates',
        auth: { token, clientType: 'machine-scoped', machineId, happyClient: 'cli-daemon/seed' },
    })
    const live = created.filter(({ seed }) => !seed.archived).map(({ id }) => io(ws, {
        transports: ['websocket'],
        path: '/v1/updates',
        auth: { token, clientType: 'session-scoped', sessionId: id, happyClient: 'cli-coding-session/seed' },
    }))
    const beat = () => {
        machine.emit('machine-alive', { machineId, time: Date.now() })
        created.filter(({ seed }) => !seed.archived).forEach(({ id }, index) => {
            live[index].volatile.emit('session-alive', { sid: id, time: Date.now(), thinking: false, mode: 'remote' })
        })
    }
    machine.on('connect', beat)
    setInterval(beat, 15_000)
    console.error('keeping the computer and its sessions online; Ctrl-C to stop')
}

void tweetnacl
main().catch((error) => {
    console.error(error?.response?.data ?? error)
    process.exit(1)
})
