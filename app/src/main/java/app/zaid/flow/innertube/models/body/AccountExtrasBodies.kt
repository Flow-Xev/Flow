package app.zaid.flow.innertube.models.body

import app.zaid.flow.innertube.models.Context
import kotlinx.serialization.Serializable

/** `playlist/get_add_to_playlist`: the account's playlists, each marked with whether it holds [videoIds]. */
@Serializable
data class GetAddToPlaylistBody(
    val context: Context,
    val videoIds: List<String>,
)

/** `notification/modify_channel_preference`; [params] is the token of one bell option. */
@Serializable
data class ModifyChannelPreferenceBody(
    val context: Context,
    val params: String,
)

/** `browse/edit_playlist` as the account with the site's own remove-by-id action. */
@Serializable
data class AccountEditPlaylistBody(
    val context: Context,
    val playlistId: String,
    val actions: List<AccountPlaylistAction>,
)

@Serializable
data class AccountPlaylistAction(
    val action: String,
    val addedVideoId: String? = null,
    val removedVideoId: String? = null,
)
