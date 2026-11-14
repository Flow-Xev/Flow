package app.zaid.flow.innertube

/**
 * What youtube.com's own page (its `ytcfg` block) says about the signed-in account, read once per cookie.
 * This is the session bootstrap YouTube.js and yt-dlp do before any signed-in call: the web client never
 * invents these values, it takes them from the page it was served with the account's cookie.
 *
 * @property visitorData the visitor id the page carries for this account. Sending it with the account's
 *   cookie is the request the browser makes; an unrelated visitor id (or none at all) is what let YouTube
 *   answer an account's Home as a generic, non-personalised feed while still flagging it as signed in.
 * @property delegatedId the channel identity the account is browsing as, only when it is not the Google
 *   account's primary one (a brand account). Null for a primary identity.
 * @property loggedIn the page's own `LOGGED_IN` flag; null when the page did not carry one.
 * @property clientVersion the live WEB client version the page was built for.
 */
internal data class AccountWebSession(
    val visitorData: String?,
    val delegatedId: String?,
    val loggedIn: Boolean?,
    val clientVersion: String?,
)

private fun clientVersionParts(version: String): List<Int> = version.split('.').map { it.trim().toIntOrNull() ?: 0 }

private val ClientVersionOrder =
    Comparator<String> { a, b ->
        val x = clientVersionParts(a)
        val y = clientVersionParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return@Comparator c
        }
        0
    }

/**
 * The newest of several WEB client versions (`2.20261002.01.00` shape), compared part by part. Blank values are
 * ignored; null when none is usable. The Home browse sends the newest it knows: the live version from the account
 * page, the one the extractor reports, or the compiled-in one, whichever is highest, never an older fallback.
 */
internal fun newestClientVersion(vararg versions: String?): String? =
    versions.filterNotNull().filter { it.isNotBlank() }.maxWithOrNull(ClientVersionOrder)

private val VISITOR_DATA_KEY =Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
private val CONTEXT_VISITOR_DATA = Regex(""""visitorData"\s*:\s*"([^"]+)"""")
private val DATASYNC_ID_KEY = Regex(""""DATASYNC_ID"\s*:\s*"([^"]*)"""")
private val DELEGATED_SESSION_ID_KEY = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]*)"""")
private val LOGGED_IN_KEY = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
private val CLIENT_VERSION_KEY = Regex(""""INNERTUBE_CONTEXT_CLIENT_VERSION"\s*:\s*"([^"]+)"""")

/**
 * Reads an [AccountWebSession] from the HTML of youtube.com's home page. Returns null when the page carries
 * none of the values (a consent or sign-in page, an error page), so the caller falls back to what it already did.
 *
 * The delegated id follows yt-dlp: `DELEGATED_SESSION_ID` when present, otherwise the `DATASYNC_ID` of a secondary
 * identity. That one has the form `channelId||userId`, whereas a primary identity is `userId||` with nothing after
 * the bars and needs no delegation.
 */
internal fun parseAccountWebSession(html: String): AccountWebSession? {
    val visitor =
        (VISITOR_DATA_KEY.find(html) ?: CONTEXT_VISITOR_DATA.find(html))
            ?.groupValues
            ?.get(1)
            ?.takeIf(String::isNotBlank)
    val loggedIn = LOGGED_IN_KEY.find(html)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    val clientVersion = CLIENT_VERSION_KEY.find(html)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
    val delegated =
        DELEGATED_SESSION_ID_KEY
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.takeIf(String::isNotBlank)
            ?: DATASYNC_ID_KEY
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.split("||")
                ?.takeIf { it.size >= 2 && it[1].isNotBlank() }
                ?.get(0)
                ?.takeIf(String::isNotBlank)
    if (visitor == null && loggedIn == null && clientVersion == null && delegated == null) return null
    return AccountWebSession(visitor, delegated, loggedIn, clientVersion)
}
