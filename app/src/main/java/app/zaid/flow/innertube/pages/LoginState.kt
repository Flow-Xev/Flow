package app.zaid.flow.innertube.pages

import kotlinx.serialization.json.JsonObject

/**
 * Whether YouTube served this response to a signed-in account, from the tracking params it stamps
 * on every one (`logged_in` on the feedback service, `yt_li` on the timing service). A browse that
 * does not recognise the session is still a perfectly valid response: it is the anonymous home
 * feed, with nothing to tell it apart from the account's own. Null when the response carries
 * neither flag, so a page without them is never mistaken for a rejected session.
 */
internal fun JsonObject.isLoggedInResponse(): Boolean? {
    val services =
        this["responseContext"]
            .objectOrNull()
            ?.get("serviceTrackingParams")
            .arrayOrNull() ?: return null
    var state: Boolean? = null
    for (service in services) {
        val params = service.objectOrNull()?.get("params").arrayOrNull() ?: continue
        for (param in params) {
            val entry = param.objectOrNull() ?: continue
            val key = entry["key"].stringOrNull()
            if (key != "logged_in" && key != "yt_li") continue
            when (entry["value"].stringOrNull()) {
                "1" -> return true
                "0" -> state = false
            }
        }
    }
    return state
}

/** Thrown when YouTube answered a signed-in request as if nobody were signed in. */
class AccountSessionRejectedException(
    message: String,
) : Exception(message)
