package app.zaid.flow.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import app.zaid.flow.R

/** Full-colour Flow Play Dark artwork for the expanded notification card.
 * Android status-bar small icons are system-tinted monochrome silhouettes.
 */
internal fun flowPlayDarkNotificationIcon(context: Context): Bitmap {
    val size = (context.resources.displayMetrics.density * 48f).toInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val drawable = requireNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher_flow_play_dark))
    drawable.setBounds(0, 0, size, size)
    drawable.draw(Canvas(bitmap))
    return bitmap
}
