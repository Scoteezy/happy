/**
 * Seeds a local Happy server with a throwaway account, a computer and a set
 * of sessions, then keeps them present (machine-alive, session-alive) until
 * stopped, so the Android and iOS apps can be compared screen by screen on
 * the same data without a real account or a real agent. The computer also
 * answers the daemon's start: a session asked for from the app is made,
 * kept online, and answers each prompt with one short turn. Sessions waiting
 * on a permission, a question form and an unknown request, one with a goal,
 * and inactive ones (one it resumes, one only a terminal can) cover the bars
 * above the composer; their answers land as a CLI's would.
 *
 * Run from packages/happy-cli, against `pnpm --filter happy-server standalone:dev`
 * (--reset-chat writes the transcript again):
 *
 *     npx tsx ../happy-android/tools/dev/seed-local-server.ts --server http://localhost:3005 [--secret <base64url>] [--offline] [--reset-chat] [--reset-permission] [--setting key=value]
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
    decryptLegacy,
    decryptWithDataKey,
    encryptLegacy,
    encryptWithDataKey,
    getRandomBytes,
    libsodiumEncryptForPublicKey,
    libsodiumPublicKeyFromSecretKey,
} from '../../../happy-cli/src/api/encryption'
import { deriveKey } from '../../../happy-cli/src/utils/deriveKey'
import { createHash } from 'node:crypto'
import { execSync } from 'node:child_process'
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { deflateSync } from 'node:zlib'
import { rgbaToThumbHash } from 'thumbhash'
import {
    closeClaudeTurnWithStatus,
    mapClaudeLogMessageToSessionEnvelopes,
    type ClaudeSessionProtocolState,
} from '../../../happy-cli/src/claude/utils/sessionProtocolMapper'

const args = process.argv.slice(2)
const option = (name: string) => {
    const index = args.indexOf(`--${name}`)
    return index >= 0 ? args[index + 1] : undefined
}
const server = option('server') ?? 'http://localhost:3005'
const offline = args.includes('--offline')
const resetChat = args.includes('--reset-chat')
const resetPermission = args.includes('--reset-permission')
/** --setting key=value (repeatable): account settings written before seeding, as JSON values (`groupToolCalls=true`). */
const settingOverrides = args.flatMap((arg, index) => arg === '--setting' && args[index + 1] ? [args[index + 1]] : []).map((pair) => {
    const at = pair.indexOf('=')
    const raw = pair.slice(at + 1)
    let value: unknown = raw
    try { value = JSON.parse(raw) } catch { /* a bare string */ }
    return [pair.slice(0, at), value] as const
})
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
    /** The agent state it is created with (a pending permission request, a form, a goal). */
    agentState?: object
    /** More metadata: the agent's own session id, which resume needs. */
    metadata?: object
    /** The computer it ran on, when not the seeded one. */
    machine?: string
    /** A one-turn transcript: the person's prompt and the agent's answer. */
    chat?: [string, string]
}

const home = '/Users/sam'
/** The ask-demo session's AskUserQuestion: one single choice, one multiple. */
const askQuestions = [
    {
        header: 'Runner', question: 'Which test runner should the website use?', multiSelect: false,
        options: [
            { label: 'Vitest', description: 'Fast, shares the Vite config' },
            { label: 'Jest', description: 'What the other packages use' },
        ],
    },
    {
        header: 'Coverage', question: 'What should the first tests cover?', multiSelect: true,
        options: [
            { label: 'Navigation' },
            { label: 'Signup form' },
            { label: 'Pricing table' },
        ],
    },
]
const machineId = 'seed-macbook'
const sessions: SessionSeed[] = [
    { tag: 'fix-login', summary: 'Fix the login redirect loop', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 3 },
    {
        tag: 'perm-demo', summary: 'Clean up the build cache', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 1,
        agentState: {
            controlledByUser: false,
            requests: { toolu_rm: { tool: 'Bash', arguments: { command: 'rm -rf .cache/build', description: 'Remove the stale build cache' }, createdAt: Date.now() } },
            completedRequests: {},
        },
    },
    {
        tag: 'ask-demo', summary: 'Pick a test runner', path: `${home}/code/website`, flavor: 'claude', minutesAgo: 2,
        agentState: {
            controlledByUser: false,
            requests: { toolu_ask: { tool: 'AskUserQuestion', arguments: { questions: askQuestions }, createdAt: Date.now() } },
            completedRequests: {},
        },
    },
    {
        // A form the chat can't show inline (a written answer): the banner and the answer sheet.
        tag: 'form-demo', summary: 'Name the release', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 4,
        chat: ['Cut the release once the tests pass.', 'The tests pass. Before I tag it, I need a name for the release and where to announce it.'],
        agentState: {
            controlledByUser: false, requests: {}, completedRequests: {},
            communications: {
                comm_release: {
                    kind: 'form', createdAt: Date.now(),
                    form: {
                        questions: [
                            { id: 'name', header: 'Release', question: 'What should the release be called?' },
                            {
                                id: 'channels', header: 'Channels', question: 'Where should it be announced?', multiSelect: true, required: false,
                                options: [
                                    { label: 'Discord', description: 'The #announcements channel' },
                                    { label: 'Blog' },
                                    { label: 'Changelog', description: 'In the app, under What’s new' },
                                ],
                            },
                        ],
                    },
                },
            },
            completedCommunications: {},
        },
    },
    {
        // A kind this build can't show: the banner offers Dismiss only.
        tag: 'notice-demo', summary: 'Review the deploy plan', path: `${home}/code/happy-server`, flavor: 'claude', minutesAgo: 6,
        chat: ['Plan the deploy of the new sync server.', 'I wrote the deploy plan and sent it to you for review.'],
        agentState: {
            controlledByUser: false, requests: {}, completedRequests: {},
            communications: { comm_review: { kind: 'plan-review', title: 'Review the deploy plan', createdAt: Date.now() } },
            completedCommunications: {},
        },
    },
    {
        // The agent's goal, set by this very Claude session: the goal bar with its three actions.
        tag: 'goal-demo', summary: 'Get the sync tests green', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 8,
        chat: ['/goal Get the sync tests green on CI before the release', 'Working on it: three sync tests fail on CI only. I am starting with the reconnect one.'],
        metadata: { claudeSessionId: 'claude-goal-1' },
        agentState: {
            controlledByUser: false, requests: {}, completedRequests: {},
            agentGoalStatus: {
                status: 'active', source: 'claude', sourceSessionId: 'claude-goal-1',
                text: 'Get the sync tests green on CI before the release',
                capabilities: { edit: true, stop: true, clear: true },
            },
        },
    },
    // A mermaid diagram and a picture in the agent's markdown.
    {
        tag: 'diagram-demo', summary: 'Sketch the reconnect flow', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 11,
        chat: ['Sketch how the sync client reconnects.', [
            'Here is the reconnect flow as it stands:',
            '',
            '```mermaid',
            'sequenceDiagram',
            '    participant App',
            '    participant Socket',
            '    participant Server',
            '    App->>Socket: connect()',
            '    Socket->>Server: handshake (token)',
            '    Server-->>Socket: connected',
            '    Socket-->>App: Connected',
            '    App->>Server: GET /v2/sessions',
            '    Server-->>App: sessions',
            '```',
            '',
            'And the palette the banner uses:',
            '',
            `![Banner gradient](data:image/png;base64,${gradientPng()})`,
        ].join('\n')],
    },
    // A screenshot the person attached, uploaded encrypted, then their question about it.
    { tag: 'photo-demo', summary: 'Fix the clipped header', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 9 },
    // TodoWrite, a sub-agent, an MCP call and a plan: the tools' own views.
    { tag: 'tools-demo', summary: 'Plan the reconnect fix', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 10 },
    { tag: 'dark-mode', summary: 'Add dark mode to settings', path: `${home}/code/happy/.dev/worktree/dark-mode`, flavor: 'claude', minutesAgo: 12 },
    { tag: 'api-docs', summary: 'Write API docs for sessions', path: `${home}/code/happy-server`, flavor: 'codex', minutesAgo: 47 },
    { tag: 'flaky-tests', summary: 'Investigate flaky sync tests', path: `${home}/code/happy/.dev/worktree/flaky-tests`, flavor: 'claude', minutesAgo: 95 },
    { tag: 'landing', summary: 'Landing page copy pass', path: `${home}/code/website`, flavor: 'claude', minutesAgo: 60 * 5 },
    { tag: 'old-migration', summary: 'Migrate settings to v2', path: `${home}/code/happy`, flavor: 'claude', archived: true, minutesAgo: 60 * 26 },
    { tag: 'old-icons', summary: 'Regenerate the icon font', path: `${home}/code/happy`, flavor: 'codex', archived: true, minutesAgo: 60 * 24 * 3 },
    // A Happy Agent session in the same checkout: its changes come from the
    // agent's own git RPCs (gitState, readFile, readFileAtRevision).
    {
        tag: 'agent-changes', summary: 'Tune the reconnect backoff', path: `${home}/code/happy`, flavor: 'claude', minutesAgo: 5,
        chat: ['Make the reconnect back off.', 'Reconnects now wait 500 ms, doubling up to 30 s.'],
        metadata: {
            client: { id: 'rig', name: 'Happy Agent', version: '1.0.0' },
            rigMetadataVersion: 1,
            capabilities: {
                abort: true, attachments: { enabled: false, maxBytes: 0, mediaTypes: [] },
                files: { browse: true, read: true, search: false, write: false },
                modelSelection: false, reasoningSelection: false, permissionModeSelection: false, resume: false,
                rpcMethods: ['gitState', 'readFile', 'readFileAtRevision'], shell: false, steering: false,
            },
        },
    },
    // Inactive with the agent's id: Resume on the seeded computer, which answers it.
    {
        tag: 'resume-demo', summary: 'Tidy the release notes', path: `${home}/code/happy`, flavor: 'claude', archived: true, minutesAgo: 60 * 3,
        chat: ['Tidy the release notes.', 'Done: grouped by area, and the typos are fixed.'],
        metadata: { claudeSessionId: 'claude-resume-1' },
    },
    // Inactive on a computer this account no longer has: the commands that resume it from a terminal.
    {
        tag: 'old-laptop', summary: 'Port the CLI to Windows', path: `${home}/code/happy-cli`, flavor: 'claude', archived: true, minutesAgo: 60 * 30,
        chat: ['Port the CLI to Windows.', 'The paths and the shell calls now work on Windows. The daemon still needs a service wrapper.'],
        metadata: { claudeSessionId: 'claude-old-1' }, machine: 'seed-old-laptop',
    },
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

    // Account settings are sealed with the secret itself, as the apps write them.
    if (settingOverrides.length > 0) {
        const current = await axios.get(`${server}/v1/account/settings`, { headers })
        const stored = current.data.settings ? decryptLegacy(decodeBase64(current.data.settings), secret) ?? {} : {}
        const settings = { ...stored, ...Object.fromEntries(settingOverrides) }
        const written = await axios.post(`${server}/v1/account/settings`, {
            settings: encodeBase64(encryptLegacy(settings, secret)),
            expectedVersion: current.data.settingsVersion,
        }, { headers })
        console.error('settings', JSON.stringify(Object.fromEntries(settingOverrides)), written.data.success ? 'written' : written.data.error)
    }

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
    // The daemon publishes that it takes resume-happy-session.
    const machineMetadata = {
        host: 'MacBook Pro',
        platform: 'darwin',
        happyCliVersion: '1.0.0',
        homeDir: home,
        happyHomeDir: `${home}/.happy`,
        happyLibDir: `${home}/.happy/lib`,
        cliAvailability: { claude: true, codex: true, gemini: false, openclaw: false, agy: false, detectedAt: Date.now() },
        resumeSupport: { rpcAvailable: true, requiresSameMachine: true, detectedAt: Date.now() },
    }
    const registered = await axios.post(`${server}/v1/machines`, {
        id: machineId,
        metadata: encodeBase64(encryptWithDataKey(machineMetadata, machineKey)),
        daemonState: encodeBase64(encryptWithDataKey({ status: 'running', pid: 4242, startedAt: Date.now() }, machineKey)),
        dataEncryptionKey: seal(machineKey),
    }, { headers })

    // Opens a data key sealed to the account: a session made on an earlier
    // run comes back with the key it was made with.
    const contentSecret = new Uint8Array(createHash('sha512').update(await deriveKey(secret, 'Happy EnCoder', ['content'])).digest()).slice(0, 32)
    const unseal = (sealed: string) => {
        const bytes = decodeBase64(sealed).slice(1)
        const opened = tweetnacl.box.open(bytes.slice(56), bytes.slice(32, 56), bytes.slice(0, 32), contentSecret)
        if (!opened) throw new Error('could not open a session key')
        return opened
    }

    // A computer registered on an earlier run keeps the key it was made with; its RPCs are sealed with that one.
    const machineDataKey = registered.data.machine?.dataEncryptionKey ? unseal(registered.data.machine.dataEncryptionKey) : machineKey

    const created: { id: string; seed: SessionSeed; key: Uint8Array; agentStateVersion: number }[] = []
    // --reset-chat / --reset-permission: the sessions holding those transcripts
    // is deleted and made again, so it is written afresh.
    const stales = [...(resetChat ? ['fix-login', 'tools-demo', 'photo-demo'] : []), ...(resetPermission ? sessions.filter(({ agentState }) => agentState).map(({ tag }) => tag) : [])]
    for (const tag of stales) {
        const stale = await axios.post(`${server}/v1/sessions`, {
            tag,
            metadata: encodeBase64(encryptWithDataKey({}, getRandomBytes(32))),
            agentState: null,
            dataEncryptionKey: seal(getRandomBytes(32)),
        }, { headers })
        await axios.delete(`${server}/v1/sessions/${stale.data.session.id}`, { headers })
    }

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
                machineId: seed.machine ?? machineId,
                flavor: seed.flavor,
                summary: { text: seed.summary, updatedAt: Date.now() - seed.minutesAgo * 60_000 },
                ...seed.metadata,
            }, key)),
            agentState: seed.agentState ? encodeBase64(encryptWithDataKey(seed.agentState, key)) : null,
            dataEncryptionKey: seal(key),
        }, { headers })
        const id: string = response.data.session.id
        created.push({ id, seed, key: unseal(response.data.session.dataEncryptionKey), agentStateVersion: response.data.session.agentStateVersion ?? 0 })
        if (seed.archived) {
            await axios.post(`${server}/v1/sessions/${id}/archive`, {}, { headers })
        }
    }

    // The transcripts, each written once: the finished turn, and the ones waiting on the person.
    await writeTranscript(created.find(({ seed }) => seed.tag === 'fix-login')!, transcript())
    await writeTranscript(created.find(({ seed }) => seed.tag === 'tools-demo')!, toolsTranscript())
    await writeTranscript(created.find(({ seed }) => seed.tag === 'photo-demo')!, await photoTranscript(created.find(({ seed }) => seed.tag === 'photo-demo')!))

    /** The photo-demo chat: the screenshot sealed with the session's blob key and uploaded, as a phone sends it, then the prompt and a reply. */
    async function photoTranscript(chat: { id: string; key: Uint8Array }): Promise<unknown[]> {
        const existing = await axios.get(`${server}/v3/sessions/${chat.id}/messages?after_seq=0&limit=1`, { headers })
        if ((existing.data.messages ?? []).length > 0) return []
        const picture = mockupPicture()
        const blobKey = await deriveKey(chat.key, 'Happy Blobs', ['session'])
        const nonce = getRandomBytes(tweetnacl.secretbox.nonceLength)
        const sealed = Buffer.concat([Buffer.from(nonce), Buffer.from(tweetnacl.secretbox(new Uint8Array(picture.bytes), nonce, blobKey))])
        const name = 'header-clipped.png'
        const upload = await axios.post(`${server}/v1/sessions/${chat.id}/attachments/request-upload`, { filename: name, size: sealed.length }, { headers })
        await axios.put(upload.data.uploadUrl, sealed, { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/octet-stream' } })
        const prompt = 'The header gets cut off on this screen. Can you see why?'
        return [
            {
                role: 'session', meta: { sentFrom: 'ios' },
                content: {
                    id: randomUUID(), time: Date.now(), role: 'user',
                    ev: { t: 'file', ref: upload.data.ref, name, size: picture.bytes.length, image: { width: picture.width, height: picture.height, thumbhash: picture.thumbhash } },
                },
            },
            { role: 'user', content: { type: 'text', text: prompt }, meta: { sentFrom: 'ios' } },
            ...reply(prompt, 'Yes: the header sits under the status bar because the screen ignores the top safe-area inset. Padding the header by the inset fixes it.'),
        ]
    }
    for (const [tag, turn] of Object.entries(turns)) {
        await writeTranscript(created.find(({ seed }) => seed.tag === tag)!, turn.transcript())
    }
    for (const session of created.filter(({ seed }) => seed.chat)) {
        const [prompt, text] = session.seed.chat!
        await writeTranscript(session, [{ role: 'user', content: { type: 'text', text: prompt }, meta: { sentFrom: 'ios' } }, ...reply(prompt, text)])
    }

    /** Writes [records]; unless [append], only into a session that has none yet. */
    async function writeTranscript(chat: { id: string; key: Uint8Array }, records: unknown[], append = false) {
        const existing = await axios.get(`${server}/v3/sessions/${chat.id}/messages?after_seq=0&limit=1`, { headers })
        if (!append && (existing.data.messages ?? []).length > 0) return
        // One at a time, a few milliseconds apart: the apps order a chat by
        // when each record was written, and a batch writes them all at once.
        // An envelope carries its own time, from when it was mapped, where a
        // user record takes the server's, so each envelope is stamped now.
        for (const built of records as { role: string; content: object }[]) {
            const content = built.role === 'session' ? { ...built, content: { ...built.content, time: Date.now() } } : built
            const record = { content: encodeBase64(encryptWithDataKey(content, chat.key)), localId: randomUUID() }
            await axios.post(`${server}/v3/sessions/${chat.id}/messages`, { messages: [record] }, { headers })
            await new Promise((resolve) => setTimeout(resolve, 5))
        }
    }

    console.log(JSON.stringify({
        server,
        secret: encodeBase64Url(secret),
        machineId,
        sessions: created.map(({ id, seed }) => ({ id, tag: seed.tag, archived: !!seed.archived })),
    }, null, 2))

    if (offline) return

    /** What the fork sheets list as rewind points: the turns a session's transcript would hold. */
    const rewindPrompts = [
        'Sync loses the reconnect event on flaky networks. Plan a fix, look around first.',
        'Write the failing test first, then the fix.',
        'Shorter commit message please, and keep the changelog entry to one line.',
    ]

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
    // A computer registered on an earlier run keeps its old metadata; it is
    // written again so it says it takes resume.
    let machineMetadataVersion: number = registered.data.machine?.metadataVersion ?? 0
    const writeMachineMetadata = (retry = true) => machine.emit('machine-update-metadata', {
        machineId,
        metadata: encodeBase64(encryptWithDataKey(machineMetadata, machineDataKey)),
        expectedVersion: machineMetadataVersion,
    }, (answer: { result: string; version?: number }) => {
        if (answer.version !== undefined) machineMetadataVersion = answer.version
        if (answer.result === 'version-mismatch' && retry) writeMachineMetadata(false)
    })
    machine.on('connect', () => {
        beat()
        writeMachineMetadata()
        for (const method of [
            'spawn-happy-session', 'resume-happy-session', 'bash', 'stop-session',
            'claude-fork-session', 'claude-duplicate-session', 'claude-list-rewind-points',
            'codex-fork-thread', 'codex-duplicate-thread', 'codex-list-rewind-points',
        ]) {
            machine.emit('rpc-register', { method: `${machineId}:${method}` })
        }
    })
    // The daemon's side of a start: a session is made where it was asked
    // for, kept online, and answers each prompt with one short turn.
    // Nothing runs; git knows no worktrees here.
    machine.on('rpc-request', async (data: { method: string; params: string }, callback: (response: string) => void) => {
        const params = decryptWithDataKey(decodeBase64(data.params), machineDataKey) as Record<string, unknown>
        const method = data.method.slice(machineId.length + 1)
        console.error('machine rpc', method, JSON.stringify(params))
        let answer: object
        if (method === 'spawn-happy-session') {
            await new Promise((resolve) => setTimeout(resolve, 1200))
            answer = { type: 'success', sessionId: await spawn(String(params.directory ?? home), String(params.agent ?? 'claude')) }
        } else if (method === 'resume-happy-session') {
            // The agent comes back under the same session, which goes online again.
            const session = created.find(({ id }) => id === params.sessionId)
            await new Promise((resolve) => setTimeout(resolve, 1500))
            if (session) {
                answerPrompts(session)
                answer = { type: 'success', sessionId: session.id }
            } else {
                answer = { type: 'error', errorMessage: 'This computer has no record of that session' }
            }
        } else if (method === 'claude-list-rewind-points' || method === 'codex-list-rewind-points') {
            // The transcript's turns, oldest first, as the CLI reads them off disk.
            const idField = method.startsWith('claude') ? 'uuid' : 'itemId'
            const now = Date.now()
            answer = {
                type: 'success',
                points: rewindPrompts.map((text, index) => ({
                    [idField]: `turn-${index + 1}`,
                    text,
                    timestamp: now - (rewindPrompts.length - index) * 47 * 60_000,
                })),
            }
        } else if (method === 'claude-fork-session' || method === 'claude-duplicate-session') {
            // A copy of the transcript, which the app then resumes with spawn-happy-session.
            answer = { type: 'success', newClaudeSessionId: `fork-${randomUUID()}` }
        } else if (method === 'codex-fork-thread' || method === 'codex-duplicate-thread') {
            answer = { type: 'success', newCodexThreadId: `fork-${randomUUID()}` }
        } else if (method === 'bash') {
            answer = { success: false, stdout: '', stderr: 'fatal: not a git repository', exitCode: 128 }
        } else {
            answer = { message: 'Session stopped' }
        }
        callback(encodeBase64(encryptWithDataKey(answer, machineDataKey)))
    })

    async function spawn(directory: string, agent: string): Promise<string> {
        const key = getRandomBytes(32)
        const response = await axios.post(`${server}/v1/sessions`, {
            tag: randomUUID(),
            metadata: encodeBase64(encryptWithDataKey({
                path: directory, host: 'MacBook Pro', homeDir: home, happyHomeDir: `${home}/.happy`, happyLibDir: `${home}/.happy/lib`,
                machineId, flavor: agent,
            }, key)),
            agentState: null,
            dataEncryptionKey: seal(key),
        }, { headers })
        const id: string = response.data.session.id
        answerPrompts({ id, key: unseal(response.data.session.dataEncryptionKey) })
        console.error('spawned', id, directory, agent)
        return id
    }

    /** Keeps a started or resumed session online, answering each prompt with one short turn. */
    function answerPrompts(chat: { id: string; key: Uint8Array }) {
        const id = chat.id
        const socket = io(ws, {
            transports: ['websocket'],
            path: '/v1/updates',
            auth: { token, clientType: 'session-scoped', sessionId: id, happyClient: 'cli-coding-session/seed' },
        })
        const alive = (thinking: boolean) => socket.volatile.emit('session-alive', { sid: id, time: Date.now(), thinking, mode: 'remote' })
        socket.on('connect', () => alive(false))
        setInterval(() => alive(false), 15_000)
        socket.on('update', async (update: { body: { t: string; sid: string; message?: { content: { t: string; c: string } } } }) => {
            if (update.body.t !== 'new-message' || update.body.sid !== id || update.body.message?.content.t !== 'encrypted') return
            const record = decryptWithDataKey(decodeBase64(update.body.message.content.c), chat.key) as { role: string; content: { text?: string } }
            if (record.role !== 'user') return
            console.error('prompt', id, JSON.stringify(record.content.text))
            alive(true)
            await new Promise((resolve) => setTimeout(resolve, 1500))
            await writeTranscript(chat, reply(record.content.text ?? ''), true)
            alive(false)
        })
    }

    // The server takes no agent state when a session is made: a session with
    // one sets it over its own socket, once (or again with --reset-permission),
    // and answers the app's permission RPC as the CLI does: the request moves
    // to completedRequests and, approved, the command "runs". A form's answer
    // or dismissal moves it to completedCommunications; a goal action edits or
    // clears the goal.
    created.filter(({ seed }) => !seed.archived).forEach((session, index) => {
        if (!session.seed.agentState) return
        const socket = live[index]
        let version = session.agentStateVersion
        let agentState = session.seed.agentState as {
            requests: Record<string, object>; completedRequests: Record<string, object>
            communications?: Record<string, object>; completedCommunications?: Record<string, object>
            agentGoalStatus?: { text: string; status: string }
        }
        const writeState = () => new Promise<void>((resolve) => {
            socket.emit('update-state', {
                sid: session.id,
                expectedVersion: version,
                agentState: encodeBase64(encryptWithDataKey(agentState, session.key)),
            }, (answer: { result: string; version?: number }) => {
                if (answer.version !== undefined) version = answer.version
                console.error('agent state', session.seed.tag, answer.result, version)
                resolve()
            })
        })
        socket.on('connect', () => {
            for (const method of ['permission', 'communication', 'goal-action']) socket.emit('rpc-register', { method: `${session.id}:${method}` })
            if (version === 0 || resetPermission) {
                agentState = {
                    ...agentState,
                    requests: Object.fromEntries(Object.entries(agentState.requests).map(([id, request]) => [id, { ...request, createdAt: Date.now() }])),
                }
                void writeState()
            }
        })
        socket.on('rpc-request', async (data: { method: string; params: string }, callback: (response: string) => void) => {
            const method = data.method.slice(session.id.length + 1)
            if (method === 'bash' || method === 'readFile') return
            if (method === 'communication') {
                const response = decryptWithDataKey(decodeBase64(data.params), session.key) as { id: string; status: string; answers?: object }
                console.error('communication', JSON.stringify(response))
                await new Promise((resolve) => setTimeout(resolve, 1200))
                const communication = agentState.communications?.[response.id]
                if (communication) {
                    const communications = { ...agentState.communications }
                    delete communications[response.id]
                    agentState = {
                        ...agentState,
                        communications,
                        completedCommunications: {
                            ...agentState.completedCommunications,
                            [response.id]: { ...communication, status: response.status, completedAt: Date.now(), answers: response.answers },
                        },
                    }
                    await writeState()
                    if (response.status === 'answered') {
                        await writeTranscript(session, reply('', `Thanks, that is what I needed: ${summarize(response.answers)}.`), true)
                    }
                }
                callback(encodeBase64(encryptWithDataKey({ ok: true }, session.key)))
                return
            }
            if (method === 'goal-action') {
                const request = decryptWithDataKey(decodeBase64(data.params), session.key) as { action: string; objective?: string }
                console.error('goal action', JSON.stringify(request))
                await new Promise((resolve) => setTimeout(resolve, 1200))
                const goal = agentState.agentGoalStatus
                if (goal && request.action === 'clear') agentState = { ...agentState, agentGoalStatus: { ...goal, status: 'inactive' } }
                if (goal && request.action === 'edit' && request.objective) agentState = { ...agentState, agentGoalStatus: { ...goal, text: request.objective } }
                await writeState()
                callback(encodeBase64(encryptWithDataKey({ ok: true }, session.key)))
                return
            }
            const answer = decryptWithDataKey(decodeBase64(data.params), session.key) as PermissionReply
            console.error('permission answer', JSON.stringify(answer))
            // A CLI takes a moment to answer; the tapped button pulses meanwhile.
            await new Promise((resolve) => setTimeout(resolve, 1500))
            const request = agentState.requests[answer.id]
            if (request) {
                const requests = { ...agentState.requests }
                delete requests[answer.id]
                agentState = {
                    ...agentState,
                    requests,
                    completedRequests: {
                        ...agentState.completedRequests,
                        [answer.id]: {
                            ...request, completedAt: Date.now(), status: answer.approved ? 'approved' : 'denied',
                            reason: answer.reason, mode: answer.mode, allowTools: answer.allowTools, allowedTools: answer.allowTools,
                        },
                    },
                }
                await writeState()
                if (answer.approved) await writeTranscript(session, turns[session.seed.tag].result(answer), true)
            }
            callback(encodeBase64(encryptWithDataKey({}, session.key)))
        })
    })
    // The sessions in ~/code/happy answer the shell and file reads the
    // changes screen makes, from a real repository with uncommitted work.
    const fixture = changesFixture()
    created.filter(({ seed }) => !seed.archived).forEach((session, index) => {
        if (session.seed.path !== `${home}/code/happy`) return
        const socket = live[index]
        if (session.seed.tag === 'agent-changes') return agentGit(session, socket, fixture)
        socket.on('connect', () => {
            for (const method of ['bash', 'readFile']) socket.emit('rpc-register', { method: `${session.id}:${method}` })
        })
        socket.on('rpc-request', (data: { method: string; params: string }, callback: (response: string) => void) => {
            const method = data.method.slice(session.id.length + 1)
            if (method !== 'bash' && method !== 'readFile') return
            const params = decryptWithDataKey(decodeBase64(data.params), session.key) as { command?: string; path?: string }
            let answer: object
            if (method === 'bash') {
                // Only git, in the fixture: the app asks for nothing else here.
                const command = String(params.command ?? '')
                if (!command.startsWith('git ')) {
                    answer = { success: false, stdout: '', stderr: 'not available in the seed', exitCode: 1 }
                } else {
                    try {
                        answer = { success: true, stdout: execSync(command, { cwd: fixture, encoding: 'utf8', shell: '/bin/sh', maxBuffer: 32 << 20 }), stderr: '', exitCode: 0 }
                    } catch (error) {
                        const failed = error as { stdout?: string; stderr?: string; status?: number }
                        answer = { success: true, stdout: failed.stdout ?? '', stderr: failed.stderr ?? '', exitCode: failed.status ?? 1 }
                    }
                }
            } else {
                const path = String(params.path ?? '')
                const local = path.startsWith(`${home}/code/happy/`) ? resolve(fixture, path.slice(`${home}/code/happy/`.length)) : null
                try {
                    if (!local || !local.startsWith(fixture)) throw new Error('outside the workspace')
                    answer = { success: true, content: readFileSync(local).toString('base64') }
                } catch (error) {
                    answer = { success: false, error: (error as Error).message }
                }
            }
            console.error('session rpc', session.seed.tag, method, params.command ?? params.path)
            callback(encodeBase64(encryptWithDataKey(answer, session.key)))
        })
    })

    setInterval(beat, 15_000)
    console.error('keeping the computer and its sessions online; Ctrl-C to stop')
}

/**
 * Happy Agent's git surface over the fixture: a snapshot against HEAD and
 * either side of a file. Counts are marked approximate, so the list shows a
 * notice above it.
 */
function agentGit(session: { id: string; key: Uint8Array }, socket: Socket, fixture: string) {
    const run = (command: string) => execSync(command, { cwd: fixture, maxBuffer: 32 << 20 })
    socket.on('connect', () => {
        for (const method of ['gitState', 'readFile', 'readFileAtRevision']) socket.emit('rpc-register', { method: `${session.id}:${method}` })
    })
    socket.on('rpc-request', (data: { method: string; params: string }, callback: (response: string) => void) => {
        const method = data.method.slice(session.id.length + 1)
        if (!['gitState', 'readFile', 'readFileAtRevision'].includes(method)) return
        const params = decryptWithDataKey(decodeBase64(data.params), session.key) as { path?: string; revision?: string }
        let answer: object
        try {
            if (method === 'gitState') {
                const head = run('git rev-parse HEAD').toString().trim()
                const statuses = new Map(run('git diff --name-status HEAD').toString().trim().split('\n').filter(Boolean).map((line) => {
                    const [code, path] = line.split('\t')
                    return [path, code === 'A' ? 'added' : code === 'D' ? 'deleted' : 'modified'] as const
                }))
                const tracked = run('git diff --numstat HEAD').toString().trim().split('\n').filter(Boolean).map((line) => {
                    const [added, removed, path] = line.split('\t')
                    const binary = added === '-'
                    return {
                        path, status: statuses.get(path) ?? 'modified', staged: false, unstaged: true, binary,
                        ...(binary ? {} : { insertions: Number(added), deletions: Number(removed) }),
                    }
                })
                const untracked = run('git ls-files --others --exclude-standard').toString().trim().split('\n').filter(Boolean).map((path) => ({
                    path, status: 'untracked', staged: false, unstaged: true, binary: false,
                    insertions: readFileSync(resolve(fixture, path), 'utf8').split('\n').filter(Boolean).length, deletions: 0,
                }))
                const files = [...tracked, ...untracked].sort((a, b) => a.path.localeCompare(b.path))
                answer = {
                    success: true,
                    git: {
                        facts: { branch: 'main', detached: false, head, upstream: 'origin/main', ahead: 0, behind: 0 },
                        comparison: 'ready', base: head,
                        changedFiles: files.length,
                        insertions: files.reduce((sum, file) => sum + (file.insertions ?? 0), 0),
                        deletions: files.reduce((sum, file) => sum + (file.deletions ?? 0), 0),
                        countsExact: false, conflicted: false, files, filesTruncated: false, scannedAt: Date.now(),
                    },
                }
            } else if (method === 'readFile') {
                const local = resolve(fixture, String(params.path))
                if (!local.startsWith(fixture)) throw new Error('outside the workspace')
                const bytes = readFileSync(local)
                answer = { success: true, content: bytes.toString('base64'), hash: createHash('sha256').update(bytes).digest('hex') }
            } else {
                const bytes = run(`git show ${String(params.revision).replace(/[^0-9a-f]/g, '')}:"${String(params.path).replace(/"/g, '')}"`)
                answer = { success: true, content: bytes.toString('base64') }
            }
        } catch (error) {
            answer = { success: false, code: 'unavailable', error: (error as Error).message.split('\n')[0] }
        }
        console.error('agent rpc', method, params.path ?? '')
        callback(encodeBase64(encryptWithDataKey(answer, session.key)))
    })
}

/**
 * A git repository with uncommitted work, standing in for ~/code/happy:
 * edits in a long file (three hunks with unchanged lines between), a small
 * edit, a new file, a deleted one, a changed picture and an edit too big to
 * open by default. Made again on every run, under .context/tmp.
 */
function changesFixture(): string {
    const root = resolve(process.cwd(), '../../.context/tmp/changes-fixture')
    rmSync(root, { recursive: true, force: true })
    const write = (path: string, content: string | Buffer) => {
        mkdirSync(dirname(resolve(root, path)), { recursive: true })
        writeFileSync(resolve(root, path), content)
    }
    const git = (command: string) => execSync(`git -c user.name=Seed -c user.email=seed@example.invalid ${command}`, { cwd: root, stdio: 'ignore' })
    const socket = (variant: 'before' | 'after') => Array.from({ length: 120 }, (_, i) => {
        const n = i + 1
        if (n === 1) return "import { io, type Socket } from 'socket.io-client'"
        if (n === 12) return variant === 'before' ? 'const RECONNECT_DELAY_MS = 1000' : 'const RECONNECT_DELAY_MS = 500\nconst RECONNECT_MAX_DELAY_MS = 30_000'
        if (n === 58) return variant === 'before' ? "    socket.on('disconnect', () => reconnect())" : "    socket.on('disconnect', (reason) => {\n        if (reason === 'io client disconnect') return\n        reconnect(backoff(attempt++))\n    })"
        if (n === 104) return variant === 'before' ? 'export function reconnect() { connect() }' : 'export function reconnect(delay = RECONNECT_DELAY_MS) {\n    setTimeout(connect, Math.min(delay, RECONNECT_MAX_DELAY_MS))\n}'
        return n % 10 === 0 ? '' : `// socket.ts line ${n}: keeps the session's updates flowing`
    }).join('\n') + '\n'
    const guard = (check: string) => [
        "import { redirect } from './redirect'",
        "import type { Session } from './session'",
        '',
        '/** Sends a signed-out visitor to /login, remembering where they were going. */',
        'export function guard(session: Session | null, path: string) {',
        '    if (!session || session.expired) {',
        '        return redirect(`/login?redirectTo=${encodeURIComponent(path)}`)',
        '    }',
        '    return null',
        '}',
        '',
        '/** /login: a visitor who is already signed in goes back where they came from. */',
        "export function login(session: Session | null, redirectTo = '/') {",
        '    ' + check,
        '        return redirect(redirectTo)',
        '    }',
        '    return null',
        '}',
        ...Array.from({ length: 60 }, (_, i) => i % 6 === 5 ? '' : `// guard.ts note ${i + 1}: every redirect is logged with the route it came from and where it went next`),
        '',
    ].join('\n')
    const schema = (variant: string) => JSON.stringify({ fields: Array.from({ length: 1100 }, (_, i) => `${variant}-field-${i}`) }, null, 0).replace(/,/g, ',\n') + '\n'
    execSync('git init -q', { cwd: (mkdirSync(root, { recursive: true }), root) })
    write('sources/sync/socket.ts', socket('before'))
    write('sources/app/settings/index.tsx', "export function Settings() {\n    return <List title=\"Settings\" />\n}\n")
    write('README.md', '# Happy\n\nMobile and web client for Claude Code.\n')
    write('sources/old/legacy.ts', 'export const legacy = true\n')
    write('assets/icon.png', png(96, 96, (x, y) => (x - 48) ** 2 + (y - 48) ** 2 < 900 ? [255, 255, 255] : [0x2b, 0xac, 0xcc]))
    write('sources/generated/schema.json', schema('old'))
    // The chat's Edit (transcript()) opens this file: the guard before and after it.
    write('src/auth/guard.ts', guard('if (session) {'))
    git('add -A')
    git('commit -q -m initial')
    write('sources/sync/socket.ts', socket('after'))
    write('sources/app/settings/index.tsx', "export function Settings() {\n    return <List title=\"Settings\" showsDarkMode />\n}\n")
    write('README.md', '# Happy\n\nMobile and web client for Claude Code and Codex.\n\nUse it from anywhere with end-to-end encryption.\n')
    rmSync(resolve(root, 'sources/old/legacy.ts'))
    write('assets/icon.png', png(96, 96, (x, y) => Math.abs(x - 48) + Math.abs(y - 48) < 36 ? [255, 255, 255] : [0xf5, 0x9e, 0x0b]))
    write('sources/generated/schema.json', schema('new'))
    write('src/auth/guard.ts', guard('if (session && !session.expired) {'))
    write('sources/sync/reconnect.ts', [
        '/** Backoff for socket reconnects: doubles from 500 ms, up to 30 s. */',
        'export function backoff(attempt: number): number {',
        '    return Math.min(500 * 2 ** attempt, 30_000)',
        '}',
        '',
    ].join('\n'))
    console.error('changes fixture', root)
    return root
}

/**
 * A short Claude turn as the app and the CLI write it: the person's prompts
 * as the app sends them, Claude's log mapped to session envelopes by the
 * CLI's own mapper. Fiction throughout.
 */
function transcript(): unknown[] {
    const { records, user, assistant, results, close } = claudeLog()

    user('After a session expires the login page redirects forever. Can you find out why?')
    assistant([
        { type: 'text', text: 'Let me look at how the login route handles an expired session.' },
        { type: 'tool_use', id: 'toolu_grep', name: 'Grep', input: { pattern: 'redirectTo', path: 'src/auth' } },
    ])
    results('toolu_grep')
    assistant([{ type: 'tool_use', id: 'toolu_read', name: 'Read', input: { file_path: `${home}/code/happy/src/auth/guard.ts` } }])
    results('toolu_read')
    assistant([
        { type: 'text', text: 'Found it: the guard sends an expired session to `/login`, and `/login` sends anyone with a session cookie back to `redirectTo`, which is the guarded page again.' },
        {
            type: 'tool_use', id: 'toolu_edit', name: 'Edit', input: {
                file_path: `${home}/code/happy/src/auth/guard.ts`,
                old_string: 'if (session) {\n    return redirect(redirectTo)\n}',
                new_string: 'if (session && !session.expired) {\n    return redirect(redirectTo)\n}',
            },
        },
    ])
    results('toolu_edit')
    assistant([{ type: 'tool_use', id: 'toolu_bash', name: 'Bash', input: { command: 'yarn test src/auth', description: 'Run the auth tests' } }])
    results('toolu_bash')
    assistant([{
        type: 'text',
        text: [
            '## Fixed',
            '',
            'The loop came from two checks that disagreed about an **expired** session:',
            '',
            '1. `guard.ts` treated it as signed out and sent it to `/login`.',
            '2. `/login` treated it as signed in and sent it back.',
            '',
            'Now `/login` only redirects a session that is still valid:',
            '',
            '```ts',
            'if (session && !session.expired) {',
            '    return redirect(redirectTo)',
            '}',
            '```',
            '',
            '| Suite | Result |',
            '| --- | --- |',
            '| auth | 24 passed |',
            '| guard | 6 passed |',
            '',
            'Want me to add a test for the expired case?',
        ].join('\n'),
    }])
    close()
    user('Yes please, and keep it short.')
    assistant([{ type: 'text', text: 'Added `expired session does not loop` to `guard.test.ts`. It fails on the old code and passes now.' }])
    close()
    return records
}

/**
 * A Claude log as the CLI maps it. A line with a [parent] is a sub-agent's,
 * under that Agent call.
 */
function claudeLog() {
    const records: unknown[] = []
    const user = (text: string) => records.push({ role: 'user', content: { type: 'text', text }, meta: { sentFrom: 'ios' } })
    const state = protocolState()
    const emit = (envelopes: unknown[]) => envelopes.forEach((content) => records.push({ role: 'session', content, meta: { sentFrom: 'cli' } }))
    const claude = (line: Record<string, unknown>) => {
        const mapped = mapClaudeLogMessageToSessionEnvelopes({ uuid: randomUUID(), ...line } as never, state)
        state.currentTurnId = mapped.currentTurnId
        emit(mapped.envelopes)
    }
    const assistant = (content: unknown[], parent?: string) =>
        claude({ type: 'assistant', parent_tool_use_id: parent, message: { role: 'assistant', model: 'claude-sonnet-4-5', content } })
    const reply = (ids: string[], parent?: string, content: unknown = 'ok') => claude({
        type: 'user', parent_tool_use_id: parent,
        message: { role: 'user', content: ids.map((id) => ({ type: 'tool_result', tool_use_id: id, content })) },
    })
    const close = () => emit(closeClaudeTurnWithStatus(state, 'completed').envelopes)
    return { records, user, assistant, results: (...ids: string[]) => reply(ids), reply, close }
}

/** The tools-demo session: a todo list, a sub-agent's work, an MCP call and a plan, each with its own view. */
function toolsTranscript(): unknown[] {
    const { records, user, assistant, results, reply, close } = claudeLog()
    const todos = (done: number) => [
        { content: 'Find where sync drops the reconnect event', status: done > 0 ? 'completed' : 'in_progress', activeForm: 'Finding the drop' },
        { content: 'Add a retry with backoff', status: done > 1 ? 'completed' : done === 1 ? 'in_progress' : 'pending', activeForm: 'Adding the retry' },
        { content: 'Cover it with a test', status: 'pending', activeForm: 'Writing the test' },
    ]
    user('Sync loses the reconnect event on flaky networks. Plan a fix, look around first.')
    assistant([{ type: 'tool_use', id: 'toolu_todo1', name: 'TodoWrite', input: { todos: todos(0) } }])
    results('toolu_todo1')
    assistant([
        { type: 'text', text: 'I will have an agent survey the sync code while I keep the list.' },
        { type: 'tool_use', id: 'toolu_agent', name: 'Agent', input: { description: 'Survey the sync client', prompt: 'Find every place the sync client handles a reconnect.', subagent_type: 'Explore' } },
    ])
    const steps: [string, string, object][] = [
        ['toolu_c1', 'Glob', { pattern: 'sources/sync/**/*.ts' }],
        ['toolu_c2', 'Grep', { pattern: 'reconnect', path: 'sources/sync' }],
        ['toolu_c3', 'Read', { file_path: `${home}/code/happy/sources/sync/socket.ts` }],
        ['toolu_c4', 'Read', { file_path: `${home}/code/happy/sources/sync/apiSocket.ts` }],
        ['toolu_c5', 'Grep', { pattern: 'onDisconnect', path: 'sources/sync' }],
    ]
    for (const [id, name, input] of steps) {
        assistant([{ type: 'tool_use', id, name, input }], 'toolu_agent')
        reply([id], 'toolu_agent')
    }
    reply(['toolu_agent'], undefined, [{ type: 'text', text: 'socket.ts drops the event when the reconnect races the auth refresh.' }])
    assistant([{ type: 'tool_use', id: 'toolu_mcp', name: 'mcp__linear__create_issue', input: { title: 'Sync drops reconnect on flaky networks', team: 'APP' } }])
    reply(['toolu_mcp'], undefined, 'Created APP-142')
    assistant([{ type: 'tool_use', id: 'toolu_log', name: 'Bash', input: { command: 'git log --oneline -4 -- sources/sync/socket.ts', description: 'Recent socket changes' } }])
    reply(['toolu_log'], undefined, [
        'a91c2e4 sync: refresh auth before reconnecting',
        '77f03b1 sync: drop stale listeners on disconnect',
        '3d5e9a0 sync: log reconnect attempts',
        'e1b4c72 sync: move socket setup out of the store',
    ].join('\n'))
    assistant([{ type: 'tool_use', id: 'toolu_todo2', name: 'TodoWrite', input: { todos: todos(1) } }])
    results('toolu_todo2')
    assistant([{
        type: 'tool_use', id: 'toolu_plan', name: 'ExitPlanMode', input: {
            plan: [
                '## Fix the dropped reconnect',
                '',
                '1. Queue the reconnect event in `socket.ts` until the auth refresh settles.',
                '2. Retry with **backoff** (250ms, doubling, at most 8s).',
                '3. Add `reconnect survives a slow refresh` to `socket.test.ts`.',
            ].join('\n'),
        },
    }])
    results('toolu_plan')
    assistant([{ type: 'text', text: 'Filed APP-142 and wrote the plan above. Shall I start on step 1?' }])
    close()
    return records
}

/** The spawned sessions' answer to a prompt: one Claude turn that says what it got. */
function reply(prompt: string, text = `Got it: “${prompt}”. This computer is the seed script, so nothing will run, but the session is real.`): unknown[] {
    const state = protocolState()
    const records: unknown[] = []
    const mapped = mapClaudeLogMessageToSessionEnvelopes({
        uuid: randomUUID(), type: 'assistant',
        message: { role: 'assistant', model: 'claude-sonnet-4-5', content: [{ type: 'text', text }] },
    } as never, state)
    state.currentTurnId = mapped.currentTurnId
    mapped.envelopes.forEach((content: unknown) => records.push({ role: 'session', content, meta: { sentFrom: 'cli' } }))
    closeClaudeTurnWithStatus(state, 'completed').envelopes.forEach((content: unknown) => records.push({ role: 'session', content, meta: { sentFrom: 'cli' } }))
    return records
}

/** A form's answers in a line: each question's picks and written answer. */
function summarize(answers: object | undefined): string {
    return Object.values((answers ?? {}) as Record<string, { options?: string[]; custom?: string }>)
        .map((answer) => [...(answer.options ?? []), answer.custom].filter(Boolean).join(', '))
        .filter(Boolean)
        .join('; ')
}

/** What the app answers a permission with (the CLI's `PermissionResponse`). */
type PermissionReply = {
    id: string; approved: boolean; reason?: string; mode?: string; allowTools?: string[]
    updatedInput?: { answers?: Record<string, string> }
}

/** A small left-to-right gradient as a PNG, for a markdown image the seed can show without the network. */
function gradientPng(width = 64, height = 32): string {
    return png(width, height, (x, y) => [Math.round(80 + (150 * x) / width), Math.round(60 + (120 * y) / height), 200]).toString('base64')
}

/** A made-up phone screenshot: a dark header, three cards and a button on a pale sky, as RGB. */
function mockupPixel(width: number, height: number) {
    return (x: number, y: number): [number, number, number] => {
        const u = x / width
        const v = y / height
        if (v < 0.12) return [28, 32, 52]
        const card = [0.18, 0.4, 0.62].findIndex((top) => v >= top && v < top + 0.18)
        if (card >= 0 && u > 0.06 && u < 0.94) {
            const tint = [[255, 255, 255], [255, 244, 228], [232, 246, 255]][card]
            return v < [0.18, 0.4, 0.62][card] + 0.035 && u < 0.5 ? [90, 96, 120] : tint as [number, number, number]
        }
        if (v > 0.86 && v < 0.94 && u > 0.2 && u < 0.8) return [92, 84, 230]
        return [Math.round(200 + 40 * v), Math.round(214 + 20 * v), 245]
    }
}

/** The mockup as a PNG and its thumbhash (from a 100-pixel-tall copy), as a phone would attach it. */
function mockupPicture(width = 600, height = 900) {
    const pixel = mockupPixel(width, height)
    const tw = Math.round((100 * width) / height)
    const th = 100
    const rgba = new Uint8Array(tw * th * 4)
    for (let y = 0; y < th; y++) for (let x = 0; x < tw; x++) {
        const [r, g, b] = pixel((x + 0.5) * width / tw, (y + 0.5) * height / th)
        rgba.set([r, g, b, 255], (y * tw + x) * 4)
    }
    return { bytes: png(width, height, pixel), width, height, thumbhash: Buffer.from(rgbaToThumbHash(tw, th, rgba)).toString('base64') }
}

/** An RGB PNG of [width]x[height], [pixel] by pixel. */
function png(width: number, height: number, pixel: (x: number, y: number) => [number, number, number] | number[]): Buffer {
    const rows = Buffer.alloc((width * 3 + 1) * height)
    for (let y = 0; y < height; y++) {
        const at = y * (width * 3 + 1)
        for (let x = 0; x < width; x++) {
            const [r, g, b] = pixel(x, y)
            rows[at + 1 + x * 3] = r
            rows[at + 2 + x * 3] = g
            rows[at + 3 + x * 3] = b
        }
    }
    const crcTable = Array.from({ length: 256 }, (_, n) => {
        let c = n
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
        return c >>> 0
    })
    const crc = (bytes: Buffer) => {
        let c = 0xffffffff
        for (const byte of bytes) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8)
        return (c ^ 0xffffffff) >>> 0
    }
    const chunk = (kind: string, data: Buffer) => {
        const body = Buffer.concat([Buffer.from(kind, 'ascii'), data])
        const length = Buffer.alloc(4)
        length.writeUInt32BE(data.length)
        const sum = Buffer.alloc(4)
        sum.writeUInt32BE(crc(body))
        return Buffer.concat([length, body, sum])
    }
    const header = Buffer.alloc(13)
    header.writeUInt32BE(width, 0)
    header.writeUInt32BE(height, 4)
    header.set([8, 2, 0, 0, 0], 8)
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0)),
    ])
}

function protocolState(): ClaudeSessionProtocolState {
    return {
        currentTurnId: null,
        uuidToProviderSubagent: new Map(),
        taskPromptToSubagents: new Map(),
        providerSubagentToSessionSubagent: new Map(),
        subagentTitles: new Map(),
        bufferedSubagentMessages: new Map(),
        hiddenParentToolCalls: new Set(),
        startedSubagents: new Set(),
        activeSubagents: new Set(),
    }
}

/**
 * A Claude turn stopped on one tool call that needs the person: the
 * prompt, Claude's text and the call; once approved, the call's result and
 * Claude's last word, closing the turn.
 */
function waitingTurn(prompt: string, text: string, call: { id: string; name: string; input: object }, done: (answer: PermissionReply) => { result: string; text: string }) {
    const state = protocolState()
    const map = (records: unknown[], line: Record<string, unknown>) => {
        const mapped = mapClaudeLogMessageToSessionEnvelopes({ uuid: randomUUID(), ...line } as never, state)
        state.currentTurnId = mapped.currentTurnId
        mapped.envelopes.forEach((content: unknown) => records.push({ role: 'session', content, meta: { sentFrom: 'cli' } }))
    }
    return {
        transcript(): unknown[] {
            const records: unknown[] = [{ role: 'user', content: { type: 'text', text: prompt }, meta: { sentFrom: 'ios' } }]
            map(records, {
                type: 'assistant',
                message: { role: 'assistant', model: 'claude-sonnet-4-5', content: [{ type: 'text', text }, { type: 'tool_use', ...call }] },
            })
            return records
        },
        result(answer: PermissionReply): unknown[] {
            const records: unknown[] = []
            const end = done(answer)
            map(records, { type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: call.id, content: end.result }] } })
            map(records, { type: 'assistant', message: { role: 'assistant', model: 'claude-sonnet-4-5', content: [{ type: 'text', text: end.text }] } })
            closeClaudeTurnWithStatus(state, 'completed').envelopes.forEach((content: unknown) => records.push({ role: 'session', content, meta: { sentFrom: 'cli' } }))
            return records
        },
    }
}

/** The sessions waiting on the person, by tag. */
const turns: Record<string, ReturnType<typeof waitingTurn>> = {
    'perm-demo': waitingTurn(
        'The build keeps picking up stale output. Clear the build cache.',
        'The stale output lives in `.cache/build`. I will remove it.',
        { id: 'toolu_rm', name: 'Bash', input: { command: 'rm -rf .cache/build', description: 'Remove the stale build cache' } },
        () => ({ result: '', text: 'Removed `.cache/build`. The next build starts clean.' }),
    ),
    'ask-demo': waitingTurn(
        'Set up tests for the website.',
        'Two quick choices before I start.',
        { id: 'toolu_ask', name: 'AskUserQuestion', input: { questions: askQuestions } },
        (answer) => {
            const answers = answer.updatedInput?.answers ?? {}
            const lines = Object.entries(answers).map(([question, picked]) => `"${question}"="${picked}"`)
            const runner = answers[askQuestions[0].question] ?? 'Vitest'
            const areas = answers[askQuestions[1].question] ?? 'navigation'
            return {
                result: `User has answered your questions: ${lines.join(', ')}. You can now continue with the user's answers in mind.`,
                text: `Setting up **${runner}**, starting with: ${areas}.`,
            }
        },
    ),
}

void tweetnacl
main().catch((error) => {
    console.error(error?.response?.data ?? error)
    process.exit(1)
})
