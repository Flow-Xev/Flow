package app.zaid.flow.account

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.zaid.flow.innertube.InnerTube
import app.zaid.flow.innertube.YouTube
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
    ) {
        @Serializable
        private data class StoredAccount(
            val profile: AccountProfile,
            val cookie: String,
        )

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
            restore()
            if (_signedIn.value) {
                val last = _lastSync.value
                if (System.currentTimeMillis() - last > RESYNC_INTERVAL_MS) {
                    scope.launch { afterSignIn() }
                }
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
                        .filter { isSignedInCookie(it.cookie) }
                _accounts.value = stored.map { it.profile }
                val wanted = prefs.getString(KEY_ACTIVE, null)
                val active = stored.firstOrNull { it.profile.id == wanted } ?: stored.firstOrNull()
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

        private fun persist() {
            runCatching {
                prefs
                    .edit()
                    .putString(KEY_ACCOUNTS, json.encodeToString(stored))
                    .putString(KEY_ACTIVE, _activeId.value)
                    .apply()
            }.onFailure { Log.w(TAG, "Could not save accounts: ${it.message}") }
        }

        // ---- Public API ----------------------------------------------------------------------

        fun setIncognito(enabled: Boolean) {
            _incognito.value = enabled
            AccountIncognito.enabled = enabled
            runCatching { prefs.edit().putBoolean(KEY_INCOGNITO, enabled).apply() }
        }

        /** Adds (or refreshes) an account from a captured Google session and makes it active. */
        fun signIn(
            cookie: String,
            name: String? = null,
        ) {
            if (!isSignedInCookie(cookie)) return
            val entryId: String
            synchronized(lock) {
                val id = idFor(cookie)
                val existing = stored.firstOrNull { it.profile.id == id }
                val profile =
                    (existing?.profile ?: AccountProfile(id = id, addedAt = System.currentTimeMillis()))
                        .let { if (name != null) it.copy(name = name) else it }
                val entry = StoredAccount(profile, cookie)
                // Re-signing in to a known account keeps its place in the list.
                stored = (stored.filterNot { it.profile.id == id } + entry).sortedBy { it.profile.addedAt }
                _accounts.value = stored.map { it.profile }
                activate(entry, persist = true)
                entryId = entry.profile.id
            }
            switchDataThenSync(entryId)
        }

        /** Makes an account that is already on the device the active one. */
        fun switchTo(id: String) {
            if (id == _activeId.value) return
            val target = synchronized(lock) { stored.firstOrNull { it.profile.id == id } } ?: return
            activate(target, persist = true)
            switchDataThenSync(target.profile.id)
        }

        /** Removes one account. If it was active, the next remaining account takes over. */
        fun removeAccount(id: String) {
            var next: StoredAccount? = null
            var wasActive = false
            synchronized(lock) {
                if (stored.none { it.profile.id == id }) return
                wasActive = _activeId.value == id
                stored = stored.filterNot { it.profile.id == id }
                _accounts.value = stored.map { it.profile }
                next = if (wasActive) stored.firstOrNull() else null
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
            dataSwapper.discard(id)
        }

        /** Signs out the active account only. */
        fun signOut() {
            _activeId.value?.let(::removeAccount)
        }

        /** Signs out of every account on this device. */
        fun signOutAll() {
            synchronized(lock) {
                stored = emptyList()
                _accounts.value = emptyList()
            }
            deactivate()
            persist()
            dataSwapper.discardAll()
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
            val usable = backup.accounts.filter { isSignedInCookie(it.cookie) }
            if (usable.isEmpty()) throw IllegalStateException("This backup holds no usable sign-in")
            var activated: StoredAccount? = null
            synchronized(lock) {
                val incomingIds = usable.map { it.profile.id }.toSet()
                stored = (stored.filterNot { it.profile.id in incomingIds } + usable).sortedBy { it.profile.addedAt }
                _accounts.value = stored.map { it.profile }
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
        ) {
            // Request state tied to the previous account must not leak into the new one.
            YouTube.dataSyncId = null
            innerTube.cookie = account.cookie
            innerTube.useLoginForBrowse = true
            _activeId.value = account.profile.id
            _signedIn.value = true
            _accountName.value = account.profile.displayName
            _lastSync.value = runCatching { prefs.getLong(syncKey(account.profile.id), 0L) }.getOrDefault(0L)
            if (persist) persist()
        }

        private fun deactivate() {
            innerTube.cookie = null
            innerTube.useLoginForBrowse = false
            YouTube.dataSyncId = null
            _activeId.value = null
            _signedIn.value = false
            _accountName.value = null
            _lastSync.value = 0L
        }

        private suspend fun loadProfile(id: String) {
            val info = YouTube.accountInfo().getOrNull() ?: return
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
                _accounts.value = stored.map { it.profile }
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
            private const val KEY_INCOGNITO = "account_incognito"
            private const val KEY_DATA_OWNER = "data_owner_account_id"
            private const val LEGACY_COOKIE = "yt_cookie"
            private const val LEGACY_NAME = "account_name"
            private const val RESYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L
        }
    }
