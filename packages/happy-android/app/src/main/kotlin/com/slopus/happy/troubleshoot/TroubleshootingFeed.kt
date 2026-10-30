package com.slopus.happy.troubleshoot

import com.slopus.happy.data.TroubleshootingSnapshot
import com.slopus.happy.home.withPresence
import com.slopus.happy.model.Machine
import com.slopus.happy.model.collectMachineChoices
import com.slopus.happy.sync.MachinePresence
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

data class TroubleshootRow(val id: String, val name: String, val online: Boolean)
data class TroubleshootState(val computers: List<TroubleshootRow>, val aiPrompt: String)

/**
 * One account-owned, off-main producer shared by every visible subscriber.
 * Last disappearance releases its observation. Read failure ends the streams
 * without clearing their screens; the next subscription starts a fresh read.
 */
class TroubleshootingFeed(
    private val snapshots: Flow<TroubleshootingSnapshot>,
    private val presence: Flow<Map<String, MachinePresence>>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val subscribers = mutableMapOf<Any, SendChannel<TroubleshootState>>()
    private var producer: Job? = null
    private var generation = 0L
    private var published: TroubleshootState? = null
    private val derivations = AtomicInteger()
    internal val computerDerivationCount: Int get() = derivations.get()

    fun snapshots(): Flow<TroubleshootState> = callbackFlow {
        val owner = checkNotNull(scope.coroutineContext[Job])
        if (!owner.isActive) { close(); return@callbackFlow }
        val id = Any()
        val stopped = owner.invokeOnCompletion { close() }
        val registration = scope.launch(io) {
            lock.withLock {
                if (!isActive || !owner.isActive) { close(); return@withLock }
                subscribers[id] = channel
                published?.let { trySend(it) }
                if (producer == null) {
                    val token = ++generation
                    producer = scope.launch(io, start = CoroutineStart.LAZY) { produce(token) }.also { it.start() }
                }
            }
        }
        awaitClose {
            stopped.dispose()
            registration.cancel()
            scope.launch(io) {
                lock.withLock {
                    subscribers.remove(id)
                    if (subscribers.isEmpty()) {
                        generation++
                        producer?.cancel()
                        producer = null
                        published = null
                    }
                }
            }
        }
    }.buffer(Channel.CONFLATED)

    private suspend fun produce(token: Long) {
        var machines: List<Machine>? = null
        var choices = emptyList<TroubleshootComputer>()
        try {
            combine(snapshots, presence) { snapshot, live ->
                val nextMachines = snapshot.machines.map { record ->
                    val machine = record.machine()
                    live[record.id]?.let(machine::withPresence) ?: machine
                }
                if (machines != nextMachines) {
                    machines = nextMachines
                    choices = collectMachineChoices(nextMachines).map { choice ->
                        TroubleshootComputer(choice.id, choice.name, choice.machineIds, choice.online, choice.activeAt,
                            listOf(choice.happyMachine?.metadata?.happyHomeDir, choice.rigMachine?.metadata?.happyHomeDir)
                                .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) })
                    }
                    derivations.incrementAndGet()
                }
                TroubleshootState(
                    choices.map { TroubleshootRow(it.id, it.name, it.online) },
                    buildOfflineMachineTroubleshooting(choices, listOfNotNull(snapshot.recentSession)).aiPrompt,
                )
            }.distinctUntilChanged().collect { state ->
                lock.withLock {
                    if (generation != token || !currentCoroutineContext().isActive) return@withLock
                    published = state
                    subscribers.values.forEach { it.trySend(state) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve the last applied content. A later visible subscription
            // recreates this producer, including its failed SQL observation.
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (generation == token) {
                        generation++
                        producer = null
                        published = null
                        val channels = subscribers.values.toList()
                        subscribers.clear()
                        channels.forEach { it.close() }
                    }
                }
            }
        }
    }
}