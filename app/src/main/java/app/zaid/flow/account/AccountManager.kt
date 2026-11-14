package app.zaid.flow.account

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.zaid.flow.account.tv.TvCredentialCache
import app.zaid.flow.account.tv.TvDeviceCode
import app.zaid.flow.account.tv.TvOAuth
import app.zaid.flow.account.tv.TvPollResult
import app.zaid.flow.account.tv.TvRefreshResult
import app.zaid.flow.account.tv.TvTokens
import app.zaid.flow.innertube.InnerTube
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.ui.AppModeState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds every account that has been signed in on this device and which one is active.
 *
 * Sessions live in an encrypted store. Exactly one account is applied to [InnerTube] at a time;
 * [switchTo] swaps it, clears the per-account request state and re-syncs.
 */
@Singleton
class AccountManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val innerTube: InnerTube,
        private val subscriptionImporter: SubscriptionImporter,
        private val accountSync: AccountSync,
        private val dataSwapper: AccountDataSwapper,
        private val visitorIdentity: app.zaid.flow.utils.potoken.VisitorIdentityStore,
    ) {
        @Serializable
        private data class StoredAccount(
            val profile: AccountProfile,
            val cookie: String,
            // Set only for an account that signed in with the YouTube TV code; its cookie is then empty and the
            // TV client signs every request (reads and writes) with this OAuth token instead.
            val tvAccess: String? = null,
            val tvRefresh: String? = null,
            val tvExpiresAt: Long = 0L,
            // Which mode this login belongs to: video-mode and music-mode accounts are separate lists.
            val music: Boolean = false,
        ) {
            val isTv: Boolean get() = tvRefresh != null
        }

        private val json = Json { ignoreUnknownKeys = true }

        private val prefs by lazy {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                "flow_account",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()

        // The scraped TV OAuth client is kept in the encrypted store, so sign-in and token refresh still work
        // when youtube.com/tv is slow or unreachable.
        private val tvAuth =
            TvOAuth(
                object : TvCredentialCache {
                    override fun load(): Pair<String, String>? {
                        val id = prefs.getString(KEY_TV_CLIENT_ID, null) ?: return null
                        val secret = prefs.getString(KEY_TV_CLIENT_SECRET, null) ?: return null
                        return id to secret
                    }

                    override fun save(credentials: Pair<String, String>) {
                        prefs.edit().putString(KEY_TV_CLIENT_ID, credentials.first).putString(KEY_TV_CLIENT_SECRET, credentials.second).apply()
                    }

                    override fun clear() {
                        prefs.edit().remove(KEY_TV_CLIENT_ID).remove(KEY_TV_CLIENT_SECRET).apply()
                    }
                },
            )
        private val tvTokenMutex = Mutex()

        // Held while history and subscriptions are swapped between accounts, and while an account's
        // import writes into them, so the two can never interleave.
        private val dataMutex = Mutex()

        // Cookies never leave this map; the UI only ever sees profiles.
        private var stored: List<StoredAccount> = emptyList()

        private val _accounts = MutableStateFlow<List<AccountProfile>>(emptyList())

        /** Every signed-in account, in the order they were added. */
        val accounts: StateFlow<List<AccountProfile>> = _accounts.asStateFlow()

        private val _activeId = MutableStateFlow<String?>(null)
        val activeId: StateFlow<String?> = _activeId.asStateFlow()

        /** The account currently used for recommendations, history and subscriptions. */
        val activeAccount: StateFlow<AccountProfile?> =
            combine(_accounts, _activeId) { list, id -> list.firstOrNull { it.id == id } }
                .stateIn(scope, SharingStarted.Eagerly, null)

        // TV accounts whose grant Google no longer honours. They stay on the device (their data is kept) until
        // the user approves a new code, which replaces the dead grant in place.
        private val reauthIds = MutableStateFlow<Set<String>>(emptySet())

        /** True while the active account is a YouTube TV sign-in whose grant was revoked: it needs a new code. */
        val tvReauthNeeded: StateFlow<Boolean> =
            combine(_activeId, reauthIds) { id, dead -> id != null && id in dead }
                .stateIn(scope, SharingStarted.Eagerly, false)

        private val _signedIn = MutableStateFlow(false)
        val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

        private val _accountName = MutableStateFlow<String?>(null)
        val accountName: StateFlow<String?> = _accountName.asStateFlow()

        private val _incognito = MutableStateFlow(false)

        /**
         * While on, nothing watched is saved to the on-device history or sent to the account's YouTube
         * history. Likes, subscriptions and comments are explicit actions and still go through.
         */
        val incognito: StateFlow<Boolean> = _incognito.asStateFlow()

        private val _syncing = MutableStateFlow(false)
        val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

        private val _lastSync = MutableStateFlow(0L)

        /** Epoch millis of the last finished sync for the active account, or 0 if never. */
        val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

        /**
         * Called after a successful sign-in or switch so the app can refresh personalized
         * recommendations.
         */
        var onSignedIn: (suspend () -> Unit)? = null

        init {
            // A browser keeps its cookies current as Google rotates them; keep the saved login current too.
            innerTube.onCookieRefreshed = { fresh -> saveRefreshedCookie(fresh) }
            restore()
            // Music mode keeps its own login: a YouTube account used for videos is not signed in there.
            scope.launch {
                AppModeState.isMusic.collect { applyMode(it) }
            }
            resyncIfStale()
            // After launch has restored the shared identity, so the account's refreshed id is the one that stays.
            scope.launch {
                delay(START_VISITOR_REFRESH_DELAY_MS)
                refreshVisitorIfStale()
            }
        }

        /** Pulls the account's history, likes and subscriptions again when the last pull is older than [RESYNC_INTERVAL_MS]. */
        fun resyncIfStale() {
            if (!_signedIn.value || _syncing.value) return
            if (System.currentTimeMillis() - _lastSync.value > RESYNC_INTERVAL_MS) {
                scope.launch { afterSignIn() }
            }
        }

        // ---- Per-mode login ------------------------------------------------------------------

        /** True while the app is in music mode; the active account below is the one chosen for that mode. */
        @Volatile private var musicMode = false

        /** The account list shown to the person: only the logins of the mode they are in. */
        private fun publishAccounts() {
            _accounts.value = stored.filter { it.music == musicMode }.map { it.profile }
        }

        private fun slotKey(music: Boolean = musicMode) = if (music) KEY_ACTIVE_MUSIC else KEY_ACTIVE

        /** Swaps in the account chosen for [music] mode, or signs the session out when that mode has none. */
        private fun applyMode(music: Boolean) {
            if (music == musicMode) return
            musicMode = music
            val wanted = prefs.getString(slotKey(music), null)
            val account = synchronized(lock) { stored.firstOrNull { it.profile.id == wanted && it.music == music } }
            publishAccounts()
            if (account == null) {
                if (_activeId.value != null) deactivate()
            } else if (account.profile.id != _activeId.value) {
                activate(account, persist = false)
                switchDataThenSync(account.profile.id)
            }
        }

        // ---- Persistence ---------------------------------------------------------------------

        private fun restore() {
            // The encrypted store can fail on a device whose keystore is unavailable; that must leave the
            // app signed out, not stop it from starting, now that this is built at launch.
            try {
                val incognitoOn = prefs.getBoolean(KEY_INCOGNITO, false)
                _incognito.value = incognitoOn
                AccountIncognito.enabled = incognitoOn
                migrateLegacySession()
                val raw = prefs.getString(KEY_ACCOUNTS, null)
                stored =
                    raw
                        ?.let { runCatching { json.decodeFromString<List<StoredAccount>>(it) }.getOrNull() }
                        .orEmpty()
                        .filter { it.isTv || isSignedInCookie(it.cookie) }
                publishAccounts()
                val wanted = prefs.getString(slotKey(), null)
                val active =
                    stored.firstOrNull { it.profile.id == wanted && it.music == musicMode }
                        ?: if (musicMode) null else stored.firstOrNull { !it.music }
                if (active != null) {
                    activate(active, persist = false)
                    // Data from before accounts kept their own history belongs to the one in use.
                    if (prefs.getString(KEY_DATA_OWNER, null) == null) {
                        prefs.edit().putString(KEY_DATA_OWNER, active.profile.id).apply()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore the saved accounts: ${e.message}")
            }
        }

        /** Older builds kept one cookie under flat keys. Fold it into the account list once. */
        private fun migrateLegacySession() {
            if (prefs.contains(KEY_ACCOUNTS)) return
            val cookie = prefs.getString(LEGACY_COOKIE, null) ?: return
            if (!isSignedInCookie(cookie)) return
            val profile =
                AccountProfile(
                    id = idFor(cookie),
                    name = prefs.getString(LEGACY_NAME, null),
                    addedAt = System.currentTimeMillis(),
                )
            val migrated = listOf(StoredAccount(profile, cookie))
            prefs
                .edit()
                .putString(KEY_ACCOUNTS, json.encodeToString(migrated))
                .putString(KEY_ACTIVE, profile.id)
                .remove(LEGACY_COOKIE)
                .remove(LEGACY_NAME)
                .apply()
        }

        /** Stores the cookie YouTube refreshed under the active web account, so the next launch starts from it. */
        private fun saveRefreshedCookie(fresh: String) {
            synchronized(lock) {
                val id = _activeId.value ?: return
                val entry = stored.firstOrNull { it.profile.id == id } ?: return
                if (entry.isTv || entry.cookie == fresh) return
                stored = stored.map { if (it.profile.id == id) it.copy(cookie = fresh) else it }
            }
            persist()
        }

        private fun persist() {
            runCatching {
                prefs
                    .edit()
                    .putString(KEY_ACCOUNTS, json.encodeToString(stored))
                    .putString(slotKey(), _activeId.value)
                    .apply()
            }.onFailure { Log.w(TAG, "Could not save accounts: ${it.message}") }
        }

        // ---- Public API ----------------------------------------------------------------------

        fun setIncognito(enabled: Boolean) {
            _incognito.value = enabled
            AccountIncognito.enabled = enabled
            if (enabled) {
                // Anything replayable from before could reveal what was searched.
                app.zaid.flow.network.InnerTubeSaverInterceptor.clear()
                app.zaid.flow.network.SelfHealingManager.wipeLastGood()
            }
            runCatching { prefs.edit().putBoolean(KEY_INCOGNITO, enabled).apply() }
        }

        /** Adds (or refreshes) an account from a captured Google session and makes it active. */
        fun signIn(
            cookie: String,
            name: String? = null,
            /** The visitor id of the signed-in web page, so the feed is requested as the session the cookie belongs to. */
            visitorData: String? = null,
        ) {
            if (!isSignedInCookie(cookie)) return
            val entryId: String
            synchronized(lock) {
                val id = (if (musicMode) "m" else "") + idFor(cookie)
                val existing = stored.firstOrNull { it.profile.id == id }
                val profile =
                    (existing?.profile ?: AccountProfile(id = id, addedAt = System.currentTimeMillis()))
                        .let { if (name != null) it.copy(name = name) else it }
                val entry = StoredAccount(profile, cookie, music = musicMode)
                // Re-signing in to a known account keeps its place in the list.
                stored = (stored.filterNot { it.profile.id == id } + entry).sortedBy { it.profile.addedAt }
                publishAccounts()
                activate(entry, persist = true, freshVisitorData = visitorData)
                entryId = entry.profile.id
            }
            switchDataThenSync(entryId)
        }

        // ---- YouTube TV code sign-in -------------------------------------------------------------

        /** Asks YouTube for the code to enter on google.com/device. */
        suspend fun tvRequestCode(): Result<TvDeviceCode> = tvAuth.requestCode()

        /** One check of whether the code was approved yet. */
        suspend fun tvPoll(deviceCode: String): TvPollResult = tvAuth.poll(deviceCode)

        /**
         * Adds an account that signed in with the YouTube TV code and makes it active. When the active TV account's
         * grant was revoked, the new tokens take over that same entry, so a re-sign-in keeps its history and place
         * in the list instead of adding a second "YouTube TV" account.
         */
        fun signInTv(tokens: TvTokens) {
            val entryId: String
            synchronized(lock) {
                val revokedId = _activeId.value?.takeIf { it in reauthIds.value && stored.any { a -> a.profile.id == it && a.isTv } }
                val id = revokedId ?: ((if (musicMode) "mtv" else "tv") + MessageDigest.getInstance("SHA-256").digest(tokens.refreshToken.toByteArray()).take(6).joinToString("") { "%02x".format(it) })
                val existing = stored.firstOrNull { it.profile.id == id }
                val profile = existing?.profile ?: AccountProfile(id = id, name = "YouTube TV", addedAt = System.currentTimeMillis())
                val entry = StoredAccount(profile, cookie = "", tvAccess = tokens.accessToken, tvRefresh = tokens.refreshToken, tvExpiresAt = tokens.expiresAtMs, music = musicMode)
                stored = (stored.filterNot { it.profile.id == id } + entry).sortedBy { it.profile.addedAt }
                publishAccounts()
                reauthIds.update { it - id }
                activate(entry, persist = true)
                entryId = id
            }
            switchDataThenSync(entryId)
        }

        /** Forgets the cached access token of [accountId] (YouTube answered 401), so the next call refreshes. */
        private fun tvExpireAccess(accountId: String) {
            synchronized(lock) {
                stored = stored.map { if (it.profile.id == accountId && it.isTv) it.copy(tvExpiresAt = 0L) else it }
            }
        }

        /**
         * A current access token for a TV account, refreshed when it is about to lapse. Null when there is none:
         * the grant was revoked (the account is then flagged in [tvReauthNeeded]), or the refresh could not be
         * reached and the old token has already lapsed. A refresh that merely cannot be reached never signs anyone out.
         */
        private suspend fun tvAccessToken(accountId: String): String? =
            tvTokenMutex.withLock {
                val account = synchronized(lock) { stored.firstOrNull { it.profile.id == accountId } } ?: return null
                val refresh = account.tvRefresh ?: return null
                val access = account.tvAccess
                if (accountId in reauthIds.value) return null
                if (access != null && account.tvExpiresAt - TV_REFRESH_MARGIN_MS > System.currentTimeMillis()) return access
                when (val result = tvAuth.refresh(refresh)) {
                    is TvRefreshResult.Fresh -> {
                        val fresh = result.tokens
                        synchronized(lock) {
                            stored =
                                stored.map {
                                    if (it.profile.id == accountId) it.copy(tvAccess = fresh.accessToken, tvRefresh = fresh.refreshToken, tvExpiresAt = fresh.expiresAtMs) else it
                                }
                        }
                        persist()
                        fresh.accessToken
                    }
                    TvRefreshResult.Revoked -> {
                        Log.w(TAG, "The YouTube TV grant for $accountId was revoked; a new sign-in is needed")
                        reauthIds.update { it + accountId }
                        null
                    }
                    // Offline or a server hiccup: a token that has not really lapsed yet still works.
                    TvRefreshResult.Unavailable ->
                        access?.takeIf { account.tvExpiresAt > System.currentTimeMillis() }
                }
            }

        /** Makes an account that is already on the device the active one. */
        fun switchTo(id: String) {
            if (id == _activeId.value) return
            val target = synchronized(lock) { stored.firstOrNull { it.profile.id == id && it.music == musicMode } } ?: return
            activate(target, persist = true)
            switchDataThenSync(target.profile.id)
        }

        /** Removes one account. If it was active, the next remaining account takes over. */
        fun removeAccount(id: String) {
            var next: StoredAccount? = null
            var wasActive = false
            synchronized(lock) {
                if (stored.none { it.profile.id == id }) return
                stored.firstOrNull { it.profile.id == id }?.tvRefresh?.let(::revokeTvGrant)
                wasActive = _activeId.value == id
                stored = stored.filterNot { it.profile.id == id }
                publishAccounts()
                // Music mode never falls back to another account on its own.
                next = if (wasActive && !musicMode) stored.firstOrNull { !it.music } else null
                // The other mode must not keep pointing at an account that no longer exists.
                val otherKey = slotKey(!musicMode)
                if (prefs.getString(otherKey, null) == id) runCatching { prefs.edit().remove(otherKey).apply() }
            }
            if (wasActive) {
                val replacement = next
                if (replacement != null) {
                    activate(replacement, persist = true)
                    switchDataThenSync(replacement.profile.id)
                } else {
                    deactivate()
                    persist()
                }
            } else {
                persist()
            }
            reauthIds.update { it - id }
            visitorIdentity.forgetAccount(id)
            dataSwapper.discard(id)
        }

        /** Revokes a TV account's grant at Google in the background, so signing out also removes this phone from its approved devices. */
        private fun revokeTvGrant(refreshToken: String) {
            scope.launch { tvAuth.revoke(refreshToken) }
        }

        /** Signs out the active account only. */
        fun signOut() {
            _activeId.value?.let(::removeAccount)
        }

        /** Signs out of every account on this device. */
        fun signOutAll() {
            synchronized(lock) {
                stored.filter { it.music == musicMode }.mapNotNull { it.tvRefresh }.forEach(::revokeTvGrant)
                stored = stored.filter { it.music != musicMode }
                publishAccounts()
            }
            deactivate()
            persist()
            runCatching { prefs.edit().remove(slotKey()).apply() }
            if (!musicMode) dataSwapper.discardAll()
        }

        /** Re-imports history, likes and subscriptions for the active account now. */
        fun syncNow() {
            if (!_signedIn.value || _syncing.value) return
            scope.launch { afterSignIn() }
        }

        /** Pulls name, email, handle and avatar for the active account. Safe to call often. */
        fun refreshProfile() {
            val id = _activeId.value ?: return
            scope.launch { loadProfile(id) }
        }

        // ---- Backup ----------------------------------------------------------------------------

        @Serializable
        private data class AccountsBackup(
            val accounts: List<StoredAccount>,
            val activeId: String?,
        )

        /** How many accounts a backup would carry. */
        val accountCount: Int get() = _accounts.value.size

        /**
         * Every account with its session, as JSON, for the caller to encrypt before it is written
         * anywhere. Null when nobody is signed in.
         */
        fun exportForBackup(): ByteArray? {
            val snapshot = synchronized(lock) { stored to _activeId.value }
            if (snapshot.first.isEmpty()) return null
            return json.encodeToString(AccountsBackup(snapshot.first, snapshot.second)).toByteArray(Charsets.UTF_8)
        }

        /**
         * Adds the backed-up accounts to the ones on this device, replacing a session that is already
         * here. The account in use stays in use; only when nobody was signed in does one become active.
         * Returns how many accounts were restored.
         */
        fun restoreFromBackup(bytes: ByteArray): Int {
            val backup =
                runCatching { json.decodeFromString<AccountsBackup>(bytes.toString(Charsets.UTF_8)) }
                    .getOrElse { throw IllegalStateException("The accounts in this backup could not be read", it) }
            val usable = backup.accounts.filter { it.isTv || isSignedInCookie(it.cookie) }
            if (usable.isEmpty()) throw IllegalStateException("This backup holds no usable sign-in")
            var activated: StoredAccount? = null
            synchronized(lock) {
                val incomingIds = usable.map { it.profile.id }.toSet()
                stored = (stored.filterNot { it.profile.id in incomingIds } + usable).sortedBy { it.profile.addedAt }
                publishAccounts()
                if (_activeId.value == null) {
                    activated = stored.firstOrNull { it.profile.id == backup.activeId } ?: stored.firstOrNull()
                }
            }
            val toActivate = activated
            if (toActivate != null) {
                // Nobody was signed in, so whatever history and subscriptions are on the device (including
                // the ones this same restore just brought in) belong to the account taking over. Without
                // claiming them here, a stale owner left by an earlier sign-out would make the swap throw
                // the restored library away.
                runCatching { prefs.edit().putString(KEY_DATA_OWNER, toActivate.profile.id).apply() }
                activate(toActivate, persist = true)
                switchDataThenSync(toActivate.profile.id)
            } else {
                persist()
            }
            return usable.size
        }

        // ---- Internals -----------------------------------------------------------------------

        private fun activate(
            account: StoredAccount,
            persist: Boolean,
            /** The visitor id captured at sign-in; it wins over whatever this account used before. */
            freshVisitorData: String? = null,
        ) {
            // Request state tied to the previous account must not leak into the new one.
            YouTube.dataSyncId = null
            if (account.isTv) {
                // The TV client signs with its OAuth token, not a cookie.
                val accountId = account.profile.id
                innerTube.cookie = null
                innerTube.useLoginForBrowse = false
                innerTube.tvTokenProvider = { tvAccessToken(accountId) }
                innerTube.tvTokenInvalidator = { tvExpireAccess(accountId) }
            } else {
                innerTube.tvTokenProvider = null
                innerTube.tvTokenInvalidator = null
                innerTube.cookie = account.cookie
                innerTube.useLoginForBrowse = true
            }
            applyVisitorIdentity(account, freshVisitorData)
            _activeId.value = account.profile.id
            _signedIn.value = true
            _accountName.value = account.profile.displayName
            _lastSync.value = runCatching { prefs.getLong(syncKey(account.profile.id), 0L) }.getOrDefault(0L)
            if (persist) persist()
        }

        /**
         * Each web account keeps its own visitor id: the one captured at its sign-in, else the one it last used.
         * Set before the account becomes active, so the feed reload that follows asks as this session.
         */
        private fun applyVisitorIdentity(
            account: StoredAccount,
            freshVisitorData: String?,
        ) {
            val id = account.profile.id
            visitorIdentity.activeAccountId = if (account.isTv) null else id
            if (account.isTv) return
            val fresh = freshVisitorData?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            if (fresh != null) {
                visitorIdentity.saveForAccount(id, fresh)
                YouTube.visitorData = fresh
            } else {
                visitorIdentity.restoreForAccount(id)?.let { YouTube.visitorData = it }
            }
        }

        /**
         * Replaces the active web account's visitor id once its saved one is too old to reuse, using the id the
         * signed-in session itself is served. A failed fetch changes nothing, and a fresh one is left alone.
         */
        fun refreshVisitorIfStale() {
            val id = _activeId.value ?: return
            if (visitorIdentity.activeAccountId != id || visitorIdentity.restoreForAccount(id) != null) return
            scope.launch {
                val fresh = YouTube.accountVisitorData().getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
                // The account may have been switched away while the request was out.
                if (_activeId.value != id) return@launch
                YouTube.visitorData = fresh
                visitorIdentity.saveForAccount(id, fresh)
            }
        }

        private fun deactivate() {
            visitorIdentity.activeAccountId = null
            innerTube.cookie = null
            innerTube.useLoginForBrowse = false
            innerTube.tvTokenProvider = null
            innerTube.tvTokenInvalidator = null
            YouTube.dataSyncId = null
            _activeId.value = null
            _signedIn.value = false
            _accountName.value = null
            _lastSync.value = 0L
        }

        private suspend fun loadProfile(id: String) {
            // A TV sign-in has no web session for the account menu; its name comes from the TV accounts list.
            val isTvAccount = synchronized(lock) { stored.firstOrNull { it.profile.id == id }?.isTv == true }
            val info = (if (isTvAccount) YouTube.tvAccountInfo() else YouTube.accountInfo()).getOrNull() ?: return
            synchronized(lock) {
                val current = stored.firstOrNull { it.profile.id == id } ?: return
                val updated =
                    current.profile.copy(
                        name = info.name,
                        email = info.email ?: current.profile.email,
                        handle = info.channelHandle ?: current.profile.handle,
                        avatarUrl = info.thumbnailUrl ?: current.profile.avatarUrl,
                    )
                stored = stored.map { if (it.profile.id == id) it.copy(profile = updated) else it }
                publishAccounts()
                if (_activeId.value == id) _accountName.value = updated.displayName
            }
            persist()
        }

        private fun isSignedInCookie(cookie: String): Boolean = cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID=")

        /** A stable id for the Google session, so signing in to the same account twice updates it. */
        private fun idFor(cookie: String): String {
            val key =
                Regex("(?:^|;\\s*)__Secure-3PAPISID=([^;]+)").find(cookie)?.groupValues?.get(1)
                    ?: Regex("(?:^|;\\s*)SAPISID=([^;]+)").find(cookie)?.groupValues?.get(1)
                    ?: cookie
            return MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
        }

        private fun syncKey(id: String) = "last_sync_$id"

        /**
         * Gives [id] its own history and subscriptions, then imports from its account. The previous
         * account's are set aside first, so nothing from one account shows up under another.
         */
        private fun switchDataThenSync(id: String) {
            val isMusicAccount = synchronized(lock) { stored.firstOrNull { it.profile.id == id }?.music == true }
            if (isMusicAccount) {
                // A music login has no video history to swap or import; it only needs its profile, and the
                // Music tab pulls its own library through MusicAccountSync.
                scope.launch {
                    loadProfile(id)
                    onSignedIn?.invoke()
                }
                return
            }
            scope.launch {
                dataMutex.withLock {
                    try {
                        ensureDataOwner(id)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not switch account data: ${e.message}")
                    }
                }
                // A sync still running for the previous account would make the import below a no-op.
                var waited = 0
                while (_syncing.value && waited++ < 50) delay(200)
                afterSignIn()
            }
        }

        private suspend fun ensureDataOwner(id: String) {
            val owner = prefs.getString(KEY_DATA_OWNER, null)
            if (owner == id) return
            if (owner != null) {
                // An owner that has since been removed is not worth keeping: its data goes with it.
                val ownerStillSignedIn = synchronized(lock) { stored.any { it.profile.id == owner } }
                dataSwapper.swap(owner, keepOutgoing = ownerStillSignedIn, incomingId = id)
            }
            prefs.edit().putString(KEY_DATA_OWNER, id).apply()
        }

        suspend fun afterSignIn() {
            val id = _activeId.value ?: return
            if (!_syncing.compareAndSet(expect = false, update = true)) return
            try {
                dataMutex.withLock {
                    // The account may have been switched away while this waited for the swap.
                    if (_activeId.value != id || prefs.getString(KEY_DATA_OWNER, null) != id) return
                    // Every account, web or TV, is read the same way from here: the repository behind these
                    // imports asks the client the account signed in with (web cookie or TV bearer token).
                    loadProfile(id)
                    subscriptionImporter.importFromAccount()
                    accountSync.syncOnLogin()
                }
                val now = System.currentTimeMillis()
                runCatching { prefs.edit().putLong(syncKey(id), now).apply() }
                if (_activeId.value == id) _lastSync.value = now
                onSignedIn?.invoke()
            } finally {
                _syncing.value = false
            }
        }

        companion object {
            private const val TAG = "AccountManager"
            private const val KEY_ACCOUNTS = "accounts_v2"
            private const val KEY_ACTIVE = "active_account_id"
            private const val KEY_ACTIVE_MUSIC = "active_music_account_id"
            private const val KEY_INCOGNITO = "account_incognito"
            private const val KEY_DATA_OWNER = "data_owner_account_id"
            private const val KEY_TV_CLIENT_ID = "tv_oauth_client_id"
            private const val KEY_TV_CLIENT_SECRET = "tv_oauth_client_secret"
            private const val TV_REFRESH_MARGIN_MS = 120_000L
            private const val LEGACY_COOKIE = "yt_cookie"
            private const val LEGACY_NAME = "account_name"
            private const val START_VISITOR_REFRESH_DELAY_MS = 4_000L
            private const val RESYNC_INTERVAL_MS = 10L * 60L * 1000L
        }
    }
