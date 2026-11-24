package app.zaid.flow.data.gif

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.zaid.flow.BuildConfig
import app.zaid.flow.data.local.KeystoreSecretBox
import app.zaid.flow.data.local.safePreferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.gifDataStore by safePreferencesDataStore(name = "gif_comments")

/**
 * The GIPHY key the comment GIF picker uses. A key pasted in Settings wins over the one built into the app;
 * it is sealed with the Android Keystore like the Gemini key.
 */
object GifSettings {
    private val keyKey = stringPreferencesKey("giphy_api_key")
    private val recentKey = stringPreferencesKey("recent_gifs")
    private val templateKey = stringPreferencesKey("send_template")
    private const val MAX_RECENT = 24

    /** The key the user saved ("" when none), for the settings row. */
    fun userKey(context: Context): Flow<String> =
        context.applicationContext.gifDataStore.data
            .map { KeystoreSecretBox.open(it[keyKey]) }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()

    /** The key searches run with: the saved one, else the build's, else "". */
    fun effectiveKey(context: Context): Flow<String> =
        userKey(context).map { it.ifBlank { BuildConfig.GIPHY_API_KEY } }.distinctUntilChanged()

    /** Whether the GIF button should show. Tenor needs no key, so it always does. */
    @Suppress("UNUSED_PARAMETER")
    fun available(context: Context): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)

    suspend fun currentKey(context: Context): String = effectiveKey(context).first()

    suspend fun setUserKey(
        context: Context,
        value: String,
    ) {
        val trimmed = value.trim()
        val sealed = if (trimmed.isEmpty()) "" else withContext(Dispatchers.IO) { KeystoreSecretBox.seal(trimmed) }
        context.applicationContext.gifDataStore.edit { preferences ->
            if (sealed.isEmpty()) preferences.remove(keyKey) else preferences[keyKey] = sealed
        }
    }

    /** The advanced "how to attach a GIF" JSON the user pasted, "" when none. */
    fun sendTemplate(context: Context): Flow<String> =
        context.applicationContext.gifDataStore.data.map { it[templateKey].orEmpty() }.distinctUntilChanged()

    suspend fun setSendTemplate(
        context: Context,
        value: String,
    ) {
        context.applicationContext.gifDataStore.edit { preferences ->
            if (value.isBlank()) preferences.remove(templateKey) else preferences[templateKey] = value.trim()
        }
    }

    /** Loads the saved format into [GifSendFormat] so the request builder can see it. */
    suspend fun applySendFormat(context: Context) {
        GifSendFormat.template = sendTemplate(context).first()
    }

    /** GIFs picked before, newest first, so a favourite is one tap away. */
    fun recents(context: Context): Flow<List<GifResult>> =
        context.applicationContext.gifDataStore.data
            .map { preferences ->
                runCatching {
                    val array = JSONArray(preferences[recentKey] ?: "[]")
                    List(array.length()) { i ->
                        val item = array.getJSONObject(i)
                        GifResult(
                            id = item.getString("id"),
                            url = item.getString("url"),
                            previewUrl = item.getString("preview"),
                            width = item.optInt("w", 200),
                            height = item.optInt("h", 200),
                        )
                    }
                }.getOrDefault(emptyList())
            }.distinctUntilChanged()

    suspend fun addRecent(
        context: Context,
        gif: GifResult,
    ) {
        val current = recents(context).first().filterNot { it.id == gif.id }
        val array = JSONArray()
        (listOf(gif) + current).take(MAX_RECENT).forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("url", it.url)
                    .put("preview", it.previewUrl)
                    .put("w", it.width)
                    .put("h", it.height),
            )
        }
        context.applicationContext.gifDataStore.edit { it[recentKey] = array.toString() }
    }
}
