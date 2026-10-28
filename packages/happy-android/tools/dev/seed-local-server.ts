/**
 * Seeds a local Happy server with a throwaway account, a computer and a set
 * of sessions, then keeps them present (machine-alive, session-alive) until
 * stopped, so the Android and iOS apps can be compared screen by screen on
 * the same data without a real account or a real agent.
 *
 * Run from packages/happy-cli, against `pnpm --filter happy-server standalone:dev`
 * (--reset-chat writes the transcript again):
 *
 *     npx tsx ../happy-android/tools/dev/seed-local-server.ts --server http://localhost:3005 [--secret <base64url>] [--offline] [--reset-chat] [--reset-permission]
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
    decryptWithDataKey,
    encryptWithDataKey,
    getRandomBytes,
    libsodiumEncryptForPublicKey,
    libsodiumPublicKeyFromSecretKey,
} from '../../../happy-cli/src/api/encryption'
import { deriveKey } from '../../../happy-cli/src/utils/deriveKey'
import { createHash } from 'node:crypto'
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
    /** The agent state it is created with (a pending permission request). */
    agentState?: object
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

    // Opens a data key sealed to the account: a session made on an earlier
    // run comes back with the key it was made with.
    const contentSecret = new Uint8Array(createHash('sha512').update(await deriveKey(secret, 'Happy EnCoder', ['content'])).digest()).slice(0, 32)
    const unseal = (sealed: string) => {
        const bytes = decodeBase64(sealed).slice(1)
        const opened = tweetnacl.box.open(bytes.slice(56), bytes.slice(32, 56), bytes.slice(0, 32), contentSecret)
        if (!opened) throw new Error('could not open a session key')
        return opened
    }

    const created: { id: string; seed: SessionSeed; key: Uint8Array; agentStateVersion: number }[] = []
    // --reset-chat / --reset-permission: the session holding that transcript
    // is deleted and made again, so it is written afresh.
    const stales = [...(resetChat ? ['fix-login'] : []), ...(resetPermission ? Object.keys(turns) : [])]
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
                machineId,
                flavor: seed.flavor,
                summary: { text: seed.summary, updatedAt: Date.now() - seed.minutesAgo * 60_000 },
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
    for (const [tag, turn] of Object.entries(turns)) {
        await writeTranscript(created.find(({ seed }) => seed.tag === tag)!, turn.transcript())
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
    // The server takes no agent state when a session is made: a session with
    // one sets it over its own socket, once (or again with --reset-permission),
    // and answers the app's permission RPC as the CLI does: the request moves
    // to completedRequests and, approved, the command "runs".
    created.filter(({ seed }) => !seed.archived).forEach((session, index) => {
        if (!session.seed.agentState) return
        const socket = live[index]
        let version = session.agentStateVersion
        let agentState = session.seed.agentState as { requests: Record<string, object>; completedRequests: Record<string, object> }
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
            socket.emit('rpc-register', { method: `${session.id}:permission` })
            if (version === 0 || resetPermission) {
                agentState = {
                    ...agentState,
                    requests: Object.fromEntries(Object.entries(agentState.requests).map(([id, request]) => [id, { ...request, createdAt: Date.now() }])),
                }
                void writeState()
            }
        })
        socket.on('rpc-request', async (data: { method: string; params: string }, callback: (response: string) => void) => {
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
    setInterval(beat, 15_000)
    console.error('keeping the computer and its sessions online; Ctrl-C to stop')
}

/**
 * A short Claude turn as the app and the CLI write it: the person's prompts
 * as the app sends them, Claude's log mapped to session envelopes by the
 * CLI's own mapper. Fiction throughout.
 */
function transcript(): unknown[] {
    const records: unknown[] = []
    const user = (text: string) => records.push({ role: 'user', content: { type: 'text', text }, meta: { sentFrom: 'ios' } })
    const state: ClaudeSessionProtocolState = {
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
    const emit = (envelopes: unknown[]) => envelopes.forEach((content) => records.push({ role: 'session', content, meta: { sentFrom: 'cli' } }))
    const claude = (line: Record<string, unknown>) => {
        const mapped = mapClaudeLogMessageToSessionEnvelopes({ uuid: randomUUID(), ...line } as never, state)
        state.currentTurnId = mapped.currentTurnId
        emit(mapped.envelopes)
    }
    const assistant = (content: unknown[]) => claude({ type: 'assistant', message: { role: 'assistant', model: 'claude-sonnet-4-5', content } })
    const results = (...ids: string[]) => claude({
        type: 'user',
        message: { role: 'user', content: ids.map((id) => ({ type: 'tool_result', tool_use_id: id, content: 'ok' })) },
    })

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
    emit(closeClaudeTurnWithStatus(state, 'completed').envelopes)
    user('Yes please, and keep it short.')
    assistant([{ type: 'text', text: 'Added `expired session does not loop` to `guard.test.ts`. It fails on the old code and passes now.' }])
    emit(closeClaudeTurnWithStatus(state, 'completed').envelopes)
    return records
}

/** What the app answers a permission with (the CLI's `PermissionResponse`). */
type PermissionReply = {
    id: string; approved: boolean; reason?: string; mode?: string; allowTools?: string[]
    updatedInput?: { answers?: Record<string, string> }
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
