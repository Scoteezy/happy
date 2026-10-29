package com.slopus.happy

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import com.slopus.happy.auth.AndroidKeyStoreKeys
import com.slopus.happy.auth.AuthApi
import com.slopus.happy.auth.AccountDisplay
import com.slopus.happy.auth.AccountRegistryFile
import com.slopus.happy.auth.AuthRepository
import com.slopus.happy.auth.CredentialLocation
import com.slopus.happy.auth.AuthState
import com.slopus.happy.auth.ExpoSecureStore
import com.slopus.happy.auth.SecureStoreCredentialStore
import com.slopus.happy.auth.SecureStoreKeys
import com.slopus.happy.config.LocalSettings
import com.slopus.happy.config.ServerConfig
import com.slopus.happy.data.HappyDatabase
import com.slopus.happy.data.HappyStore
import com.slopus.happy.data.LocalCaches
import com.slopus.happy.model.Profile
import com.slopus.happy.model.settings.ConnectApi
import com.slopus.happy.model.settings.HttpConnectApi
import com.slopus.happy.model.settings.LanguageController
import com.slopus.happy.model.settings.SettingsRepository
import com.slopus.happy.net.ConnectionStatus
import com.slopus.happy.net.HappyHttp
import com.slopus.happy.payments.PreferencesPurchasesCache
import com.slopus.happy.payments.PurchaseAccount
import com.slopus.happy.payments.PurchasesRepository
import com.slopus.happy.payments.RevenueCatPurchases
import com.slopus.happy.sync.AccountSync
import com.slopus.happy.changelog.ChangelogReadMarker
import com.slopus.happy.home.NewSessionDraftStore
import com.slopus.happy.home.PrefsReviewStamps
import com.slopus.happy.voice.ElevenLabsTransport
import com.slopus.happy.voice.StoreVoiceContext
import com.slopus.happy.voice.VoiceApi
import com.slopus.happy.voice.VoiceEngine
import com.slopus.happy.voice.VoiceLanguages
import com.slopus.happy.voice.VoiceOptions
import com.slopus.happy.voice.VoicePaywall
import com.slopus.happy.voice.VoicePreferences
import com.slopus.happy.voice.VoiceTools
import com.slopus.happy.voice.VoiceUpsell
import com.slopus.happy.chat.AgentState
import com.slopus.happy.sync.SendOptions
import com.slopus.happy.sync.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The app's object graph, built once in [HappyApplication]. Hand-wired: the
 * graph is small enough that a DI framework would cost more than it saves.
 *
 * While signed in it owns one [AccountSync] for the account (delta sync,
 * live updates, presence, history); signing out stops it before local data
 * is cleared.
 */
class AppGraph(
    context: Context,
    private val clientId: String,
    defaultServerUrl: String,
    secureStoreKeys: SecureStoreKeys = AndroidKeyStoreKeys(),
    /** Null keeps every account's database in memory (tests). */
    private val databaseName: String? = HappyDatabase.NAME,
    val directTransport: (com.slopus.happy.direct.DirectDeviceCredentials) -> com.slopus.happy.direct.DirectTransport =
        { com.slopus.happy.direct.TailcatHttpTransport(it.address, it.port) },
) {
    private val appContext: Context = context.applicationContext ?: context

    val serverConfig = ServerConfig(
        context.getSharedPreferences(ServerConfig.PREFS_NAME, Context.MODE_PRIVATE),
        defaultServerUrl,
    )
    val stemCells = com.slopus.happy.stemcells.StemCellsPreference(
        com.slopus.happy.config.AppVariant.current,
        context.getSharedPreferences(com.slopus.happy.stemcells.StemCellsPreference.PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    /** The active account's server while signed in; [serverConfig] is the one onboarding signs in to. */
    @Volatile private var activeServerUrl: String? = null
    val http = HappyHttp(okHttp, serverUrl = { activeServerUrl ?: serverConfig.url.value }, clientId = clientId)
    val authApi = AuthApi(HappyHttp(okHttp, serverUrl = { serverConfig.url.value }, clientId = clientId))
    private val secureStore = ExpoSecureStore(
        prefs = context.getSharedPreferences(ExpoSecureStore.PREFS_NAME, Context.MODE_PRIVATE),
        keys = secureStoreKeys,
        defaultPrefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE),
    )
    val auth = AuthRepository(
        SecureStoreCredentialStore(secureStore),
        authApi,
        registryStore = AccountRegistryFile(LocalCaches.registryFile(appContext)),
        serverUrl = { serverConfig.url.value },
        onSignedIn = { if (direct.state.value.activeId != null) direct.select(null) },
    )

    private val databases = HashMap<String?, HappyDatabase>()

    /**
     * [accountId]'s own database, kept open once opened (happy-ios
     * `accounts/<id>/store.sqlite`); signed out, an empty one in memory.
     */
    private fun database(accountId: String?): HappyDatabase = synchronized(databases) {
        databases.getOrPut(accountId) {
            if (databaseName == null || accountId == null) HappyDatabase(appContext, null)
            else HappyDatabase(appContext, LocalCaches.accountDatabase(appContext, accountId, databaseName).path)
        }
    }

    /** [accountId]'s device-only preferences; signed out, the device's. */
    private fun localPreferences(accountId: String?) = appContext.getSharedPreferences(
        accountId?.let { "${LocalSettings.PREFS_NAME}-$it" } ?: LocalSettings.PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val activeAccountId: String? get() = (auth.state.value as? AuthState.SignedIn)?.encryption?.anonId

    init {
        // The account this device had before several could be signed in keeps its data.
        auth.registry.value.entries.firstOrNull { it.location == CredentialLocation.Shared }?.let { shared ->
            LocalCaches.adoptLegacyAccount(appContext, shared.id, databaseName, localPreferences(shared.id))
        }
    }

    val store = HappyStore(database(activeAccountId))
    val localSettings = LocalSettings(localPreferences(activeAccountId))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val purchases = PurchasesRepository(
        scope,
        RevenueCatPurchases(appContext, BuildConfig.REVENUE_CAT_GOOGLE_KEY, BuildConfig.DEBUG),
        PreferencesPurchasesCache(context.getSharedPreferences(PreferencesPurchasesCache.PREFS_NAME, Context.MODE_PRIVATE)),
        currentAccount = { purchaseAccount() },
    )

    /** The account's synced settings; a change is uploaded by the next sync. */
    val settings = SettingsRepository(store, scope, onChanged = ::requestSync)

    /** The UI language (text/index.ts), from the synced preferredLanguage at start. */
    val language = LanguageController(context, context.getSharedPreferences(LanguageController.PREFS_NAME, Context.MODE_PRIVATE))
        .also { it.follow(settings.settings, scope) }

    /** Connected accounts (Settings › Claude Code, GitHub) for the signed-in account. */
    val connect: ConnectApi = HttpConnectApi(http) { (auth.state.value as? AuthState.SignedIn)?.credentials?.token }

    /** The newest What's New entry the user has seen (changelog/storage.ts). */
    val changelog = ChangelogReadMarker(context.getSharedPreferences(ChangelogReadMarker.PREFS_NAME, Context.MODE_PRIVATE))
    private val accountLock = Mutex()
    private val foreground = MutableStateFlow(false)
    private val accountState = MutableStateFlow<AccountSync?>(null)

    /** The signed-in account's sync, or null when signed out. */
    val account: StateFlow<AccountSync?> = accountState.asStateFlow()

    private val storeAccountState = MutableStateFlow(auth.state.value)

    /**
     * [AuthRepository.state] once [store] and [localSettings] are that
     * account's: the screens are built for this, so none shows the previous
     * account's data (happy-ios publishes a session only with its store open).
     */
    val storeAccount: StateFlow<AuthState> = storeAccountState.asStateFlow()

    val voicePreferences = VoicePreferences(context.getSharedPreferences(VoicePreferences.PREFS_NAME, Context.MODE_PRIVATE))

    /** The SDK is allocated only inside connect(), never while constructing the app graph. */
    val voice: VoiceEngine = VoiceEngine(
        scope = scope,
        service = VoiceApi(okHttp, clientId, { serverConfig.url.value }, { serverConfig.voiceUrl }, BuildConfig.ELEVENLABS_AGENT_ID),
        transport = ElevenLabsTransport(appContext),
        credentials = { (auth.state.value as? AuthState.SignedIn)?.credentials },
        options = {
            val values = settings.settings.value
            val local = localSettings.values.value
            VoiceOptions(values.voiceBypassToken, values.voiceCustomAgentId,
                VoiceLanguages.elevenLabsCode(values.voiceAssistantLanguage),
                VoiceUpsell.resolve(override = local.voiceUpsellOverride, overrideEnabled = BuildConfig.DEBUG || local.devModeEnabled))
        },
        paywall = object : VoicePaywall {
            override fun hasPro() = purchases.hasPro()
            override suspend fun present(flow: String) = purchases.present(flow).purchased
        },
        preferences = voicePreferences,
        contextFactory = {
            StoreVoiceContext(store, checkNotNull(accountState.value).messages.stored, scope, onSessionDeleted = ::onVoiceSessionDeleted)
        },
        toolsFactory = { isCurrent, onSent ->
            // Capture this call's account, never a later sign-in or whichever session UI is now open.
            val bound = checkNotNull(accountState.value)
            VoiceTools(
                send = { id, message ->
                    check(accountState.value === bound && isCurrent())
                    val result = bound.sender.send(id, message, SendOptions(source = "voice", isCurrent = { accountState.value === bound && isCurrent() }))
                    check(result is SendResult.Accepted) { "Voice message was not queued" }
                },
                permissionOwner = { requestId -> withContext(Dispatchers.IO) {
                    check(accountState.value === bound && isCurrent())
                    store.sessions().firstOrNull { AgentState.parse(it.agentStateJson)?.requests?.containsKey(requestId) == true }?.id
                } },
                decide = { id, requestId, allow ->
                    check(accountState.value === bound && isCurrent())
                    if (allow) bound.sessionOps.allow(id, requestId) else bound.sessionOps.deny(id, requestId)
                },
                sent = onSent,
                isCurrent = { accountState.value === bound && isCurrent() },
            )
        },
    )

    /** The updates socket's state; disconnected when signed out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionStatus: StateFlow<ConnectionStatus> = accountState
        .flatMapLatest { it?.connectionStatus ?: flowOf(ConnectionStatus.DISCONNECTED) }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionStatus.DISCONNECTED)

    init {
        scope.launch {
            combine(auth.state, serverConfig.url) { _, _ -> purchaseAccount() }.collect(purchases::setAccount)
        }
        scope.launch {
            foreground.collect { if (it) purchases.refresh() }
        }
    }

    private fun purchaseAccount(): PurchaseAccount? = (auth.state.value as? AuthState.SignedIn)?.publicId
        ?.takeIf { it.isNotBlank() }?.let { PurchaseAccount(serverConfig.url.value, it) }

    /** Ask for a delta sync now (sign-in, app back in the foreground). No-op when signed out. */
    fun requestSync() {
        accountState.value?.requestSync()
    }

    /** Whether the app is in the foreground (MainActivity started). */
    val isForeground: StateFlow<Boolean> = foreground.asStateFlow()

    /** The app came to the foreground or left it (MainActivity onStart/onStop). */
    fun setForeground(value: Boolean) {
        foreground.value = value
        directScope.launch { direct.setForeground(value) }
    }

    // Direct (happy-ios `AccountController.direct`)

    private val directScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val directDirectory = java.io.File(appContext.filesDir, "direct")

    /** Paired computers (Direct): each one's identity, credentials and sync engine. */
    val direct = com.slopus.happy.direct.DirectAccounts(
        com.slopus.happy.direct.SecureStoreDirectCredentialStore(secureStore),
        directDirectory,
        directScope,
        makeTransport = { directTransport(it) },
        // Each engine its own one-at-a-time scope, as the store's actor.
        makeEngine = { id, client ->
            com.slopus.happy.direct.DirectSyncEngine(client, java.io.File(directDirectory, id), CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)))
        },
        offlineDirectory = { id -> java.io.File(directDirectory, id) },
    )

    /** Published only once the selected account's store and preferences are ready. */
    data class ShellAccount(val relay: AuthState, val directId: String?)
    private val shellState = MutableStateFlow<ShellAccount?>(null)
    val shellAccount: StateFlow<ShellAccount?> = shellState.asStateFlow()
    private val restoreFailure = MutableStateFlow(false)
    val directRestoreFailed: StateFlow<Boolean> = restoreFailure.asStateFlow()
    private val shellOperationLock = Mutex()

    init {
        scope.launch {
            try {
                direct.setForeground(foreground.value)
                direct.restore()
                if (!stemCells.showsComputers) direct.select(null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the saved credentials/files untouched and leave relay
                // onboarding usable. A failed restore must not strand a blank window.
                restoreFailure.value = true
            }
            combine(auth.state, direct.state) { relay, computers -> ShellAccount(relay, computers.activeId) }
                .distinctUntilChanged()
                .collect { selected ->
                    if (selected.directId == null && selected.relay is AuthState.SignedOut && stemCells.showsComputers) {
                        val computers = direct.state.value.accounts
                        val fallback = computers.firstOrNull { !it.revoked } ?: computers.firstOrNull()
                        if (fallback != null) {
                            direct.select(fallback.id)
                            return@collect
                        }
                    }
                    accountLock.withLock {
                        if (selected.directId == null) {
                            switchAccount(selected.relay)
                        } else {
                            stopAccount()
                            localSettings.switchTo(localPreferences(selected.directId))
                        }
                        shellState.value = selected
                    }
                }
        }
    }

    suspend fun selectDirect(accountId: String) = scope.async {
        shellOperationLock.withLock {
            direct.select(accountId)
            shellAccount.first { it?.directId == accountId }
        }
        Unit
    }.await()

    /** Saving a proven pairing must finish even when the resulting home removes its screen. */
    suspend fun addDirect(credentials: com.slopus.happy.direct.DirectDeviceCredentials) = scope.async {
        shellOperationLock.withLock { direct.add(credentials) }
    }.await()

    suspend fun removeDirect(accountId: String) = scope.async {
        shellOperationLock.withLock {
            val wasActive = direct.state.value.activeId == accountId
            direct.remove(accountId)
            appContext.deleteSharedPreferences("${LocalSettings.PREFS_NAME}-$accountId")
            if (wasActive) direct.state.value.accounts.firstOrNull()?.let { direct.select(it.id) }
        }
        Unit
    }.await()

    private val logoutHooks = CopyOnWriteArrayList<suspend (authToken: String) -> Unit>()

    /**
     * [hook] runs first on every logout, while the account's token still
     * works (AuthContext.tsx unregisters the push token there). It must be
     * bounded and must not throw.
     */
    fun beforeLogout(hook: suspend (authToken: String) -> Unit) {
        logoutHooks += hook
    }

    /**
     * Settings > Account > Logout: the active account's credentials, local
     * data and device preferences go; the server choice stays, and the next
     * signed-in account takes over. Runs in the app scope, since the screen
     * that asked is gone as soon as the account is.
     */
    fun logout(): Job = scope.launch {
        accountLock.withLock {
            val signedIn = auth.state.value as? AuthState.SignedIn ?: return@withLock
            val id = signedIn.encryption.anonId
            voice.stop()
            logoutHooks.forEach { it(signedIn.credentials.token) }
            purchases.setAccount(null)
            stopAccount()
            withContext(Dispatchers.IO) {
                store.clearAll()
                // persistence.ts clearPersistence: everything in the default MMKV goes, the server choice stays.
                LocalSettings(localPreferences(id)).clear()
                changelog.clear()
                for (name in WIPED_PREFS) appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                LocalCaches.wipeShared(appContext)
                // Off its database and preferences before they go; the next account, if any, replaces these.
                if (direct.state.value.activeId == null) localSettings.switchTo(localPreferences(null))
                store.switchTo(database(null))
                removeAccountStorage(id)
            }
            wipeHooks.forEach { it() }
            auth.signOut(id)
        }
    }

    /**
     * The account switcher's sign-out (happy-ios `signOut(accountID:)`): the
     * active account as [logout]; another one leaves without touching the
     * active account, its push token dropped and its data removed.
     */
    fun signOut(accountId: String): Job {
        if (accountId == activeAccountId) return logout()
        return scope.launch {
            accountLock.withLock {
                auth.credentials(accountId)?.let { credentials -> logoutHooks.forEach { it(credentials.token) } }
                withContext(Dispatchers.IO) {
                    HappyStore(database(accountId)).clearAll()
                    localPreferences(accountId).edit().clear().commit()
                    removeAccountStorage(accountId)
                }
                auth.signOut(accountId)
            }
        }
    }

    /** A signed-out, emptied account's database closed and its files and preferences deleted. */
    private fun removeAccountStorage(accountId: String) {
        synchronized(databases) { databases.remove(accountId) }?.close()
        appContext.deleteSharedPreferences("${LocalSettings.PREFS_NAME}-$accountId")
        LocalCaches.removeAccount(appContext, accountId)
    }

    /** Settings › Account › Add Account: the sign-in screens go up over the active account. */
    fun beginAddingAccount() = auth.beginAddingAccount()

    /** They were closed without a sign-in: the active account's server is the one to sign in to again. */
    fun cancelAddingAccount() {
        auth.cancelAddingAccount()
        activeServerUrl?.let { if (it != serverConfig.url.value) serverConfig.set(it) }
    }

    /** Makes another signed-in account the active one; the screens are rebuilt for it. Throws when it can't be. */
    suspend fun switchAccount(accountId: String) = scope.async {
        shellOperationLock.withLock {
            auth.switchTo(accountId)
            if (direct.state.value.activeId != null) direct.select(null)
            shellAccount.first { it?.directId == null && (it?.relay as? AuthState.SignedIn)?.encryption?.anonId == accountId }
        }
        Unit
    }.await()

    /**
     * happy-ios `accountHoldingSession`: the signed-in account whose store
     * has [sessionId], the active one first and then by the order they were
     * added; null when none has it. Another account's database is read in
     * place and left closed unless it was already open.
     */
    suspend fun accountHoldingSession(sessionId: String): String? = withContext(Dispatchers.IO) {
        val activeId = (storeAccount.value as? AuthState.SignedIn)?.encryption?.anonId
        if (activeId != null && store.session(sessionId) != null) return@withContext activeId
        auth.registry.value.ordered.firstOrNull { entry ->
            entry.id != activeId && storedSessionExists(entry.id, sessionId)
        }?.id
    }

    private fun storedSessionExists(accountId: String, sessionId: String): Boolean {
        val open = synchronized(databases) { databases[accountId] }
        if (open != null) return HappyStore(open).session(sessionId) != null
        val file = databaseName?.let { LocalCaches.storedDatabase(appContext, accountId, it) } ?: return false
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM sessions WHERE id = ?", arrayOf(sessionId)) > 0
            }
        } catch (e: SQLiteException) {
            Log.w("AppGraph", "Could not look in ${accountId.take(8)}'s store", e)
            false
        }
    }

    private val wipeHooks = CopyOnWriteArrayList<() -> Unit>()

    /**
     * [hook] runs on logout once the stored data is gone, for in-memory
     * state that mirrors it (the new-session draft). It must not throw.
     */
    fun afterLogoutWipe(hook: () -> Unit) {
        wipeHooks += hook
    }

    private suspend fun switchAccount(state: AuthState) {
        stopAccount()
        val id = (state as? AuthState.SignedIn)?.encryption?.anonId
        // The account's own database and preferences, its server for every request.
        val server = id?.let { auth.registry.value.entry(it)?.serverUrl }
        activeServerUrl = server
        if (server != null && server != serverConfig.url.value) serverConfig.set(server)
        withContext(Dispatchers.IO) { database(id).writableDatabase }
        localSettings.switchTo(localPreferences(id))
        store.switchTo(database(id))
        storeAccountState.value = state
        if (state !is AuthState.SignedIn || id == null) return
        val accountServer = server ?: serverConfig.url.value
        val account = AccountSync(
            http = http,
            okHttp = okHttp,
            serverUrl = { accountServer },
            clientId = clientId,
            token = state.credentials.token,
            encryption = state.encryption,
            store = store,
            parent = scope.coroutineContext[Job]!!,
            foreground = foreground,
            outboxFiles = java.io.File(appContext.filesDir, "outbox"),
            fileCacheDirectory = withContext(Dispatchers.IO) { LocalCaches.prepareFileCache(appContext, state.encryption.anonId) },
        )
        accountState.value = account
        account.start()
        // What the switcher shows of this account, from its profile as it syncs.
        displayJob = scope.launch(Dispatchers.IO) {
            store.changes.map { store.profile()?.let(Profile::parse) }.distinctUntilChanged().collect { profile ->
                if (profile != null) {
                    auth.updateDisplay(id, AccountDisplay(profile.displayName, profile.github?.login, profile.avatarUrl, profile.avatar?.thumbhash))
                }
            }
        }
    }

    private var displayJob: Job? = null

    private suspend fun stopAccount() {
        displayJob?.cancel()
        displayJob = null
        voice.stop()
        val account = accountState.value ?: return
        accountState.value = null
        account.stop()
    }

    private fun onVoiceSessionDeleted(id: String) {
        if (voice.state.value.sessionId == id) scope.launch { voice.stop() }
    }
}

/**
 * Preferences that hold this account's data or choices and go with it on
 * logout (happy-app keeps them in the default MMKV, which clearPersistence
 * empties). The server choice, the secure store (cleared by [AuthRepository])
 * and the push token store (cleared by its own logout hook) are not here.
 */
private val WIPED_PREFS = listOf(NewSessionDraftStore.PREFS_NAME, PrefsReviewStamps.PREFS_NAME, VoicePreferences.PREFS_NAME)
