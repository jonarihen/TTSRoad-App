package dk.perspektiva.ttsroad.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest

/**
 * How wide a cover is fetched for the widget, in pixels.
 *
 * A `RemoteViews` bitmap crosses a Binder transaction to the launcher, and that transaction has a
 * hard ~1 MB ceiling shared with everything else in the update. At `ARGB_8888` this is 160 KB,
 * which is comfortable, and it is still more than the ~56 dp the cover is ever drawn at.
 */
private const val CoverPx = 200

/**
 * Fetch a cover as a bitmap the launcher can draw, or null.
 *
 * The launcher cannot resolve a URL — it is handed pixels — so the image has to be fetched here and
 * travel with the update. It goes through the application's Coil loader on purpose: that is the
 * client which attaches the bearer token only when the request origin exactly matches the signed-in
 * server, so a Royal Road or CDN cover is fetched anonymously, as it must be.
 *
 * Hardware bitmaps are refused because they cannot be marshalled; a hardware-backed cover would
 * throw on the way to the launcher rather than merely fail to draw.
 *
 * Any failure is a null. A cover is decoration, and a widget that cannot fetch one should still
 * show the chapter and its buttons.
 */
suspend fun loadCoverBitmap(context: Context, url: String?): Bitmap? {
    val target = url?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val request = ImageRequest.Builder(context)
            .data(target)
            .size(CoverPx)
            .allowHardware(false)
            .build()
        context.imageLoader.execute(request).drawable?.toBitmap()
    }.getOrNull()
}
