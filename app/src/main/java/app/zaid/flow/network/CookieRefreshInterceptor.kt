package app.zaid.flow.network

import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Does what a browser does with a signed-in session: reads the `Set-Cookie` headers YouTube/Google
 * answer with and hands the refreshed values back, so the saved login keeps up when Google rotates
 * its session cookies (`__Secure-1PSIDTS`, `__Secure-3PSIDTS`, the `*SIDCC` family).
 *
 * Added INSIDE the data-saver cache, so it only sees live network answers, never a replayed old one.
 * Only answers to a request that carried the account's cookie are used, and only session cookies are
 * taken. Deletions are ignored on purpose: a refresh may replace a value but never log the user out.
 */
internal class CookieRefreshInterceptor(
    private val onRefreshed: (requestCookie: String, updates: Map<String, String>) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val sent = request.header("Cookie")
        if (!sent.isNullOrBlank() && isGoogleHost(request.url.host)) {
            val headers = response.headers("Set-Cookie")
            if (headers.isNotEmpty()) {
                val updates = LinkedHashMap<String, String>()
                for (header in headers) {
                    val cookie = Cookie.parse(request.url, header) ?: continue
                    if (cookie.value.isBlank() || !isSessionCookie(cookie.name)) continue
                    updates[cookie.name] = cookie.value
                }
                if (updates.isNotEmpty()) runCatching { onRefreshed(sent, updates) }
            }
        }
        return response
    }

    private fun isGoogleHost(host: String) = host == "youtube.com" || host.endsWith(".youtube.com") || host.endsWith(".google.com")

    private fun isSessionCookie(name: String) = name.startsWith("__Secure-") || name in SESSION_NAMES

    private companion object {
        val SESSION_NAMES = setOf("SID", "HSID", "SSID", "APISID", "SAPISID", "SIDCC", "LOGIN_INFO")
    }
}
