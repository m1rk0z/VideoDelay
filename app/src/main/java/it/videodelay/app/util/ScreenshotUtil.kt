package it.videodelay.app.util

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.*
import java.io.File

object ScreenshotUtil {

    /**
     * Cattura il frame corrente del Player combinando il frame video (tramite PixelCopy)
     * e la UI circostante (HUD, pulsanti, loghi) senza schermate nere.
     */
    fun captureAndShare(context: android.content.Context, rootView: View, callback: (Boolean) -> Unit) {
        val surfaceView = findSurfaceView(rootView)

        if (surfaceView != null && surfaceView.holder?.surface?.isValid == true) {
            val sw = surfaceView.width.coerceAtLeast(1)
            val sh = surfaceView.height.coerceAtLeast(1)
            val videoBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)

            // Cattura il frame video nativo direttamente dalla SurfaceView
            PixelCopy.request(
                surfaceView,
                videoBitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        try {
                            val rw = rootView.width.coerceAtLeast(1)
                            val rh = rootView.height.coerceAtLeast(1)

                            // 1. Salviamo i background correnti del layout root e del player_view
                            val oldRootBg = rootView.background
                            rootView.background = null

                            val playerView = rootView.findViewById<View>(it.videodelay.app.R.id.player_view)
                            val oldPlayerBg = playerView?.background
                            playerView?.background = null

                            // Rendiamo temporaneamente invisibile la SurfaceView per non disegnare la scatola nera
                            val oldSurfaceVisibility = surfaceView.visibility
                            surfaceView.visibility = View.INVISIBLE

                            // 2. Cattura l'intero layout UI (lo sfondo del video sarà trasparente e i pulsanti sopra rimarranno visibili)
                            val uiBitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                            val uiCanvas = Canvas(uiBitmap)
                            rootView.draw(uiCanvas)

                            // Ripristina immediatamente lo stato originale della UI
                            rootView.background = oldRootBg
                            playerView?.background = oldPlayerBg
                            surfaceView.visibility = oldSurfaceVisibility

                            // 3. Calcola dove si trova la SurfaceView all'interno di rootView
                            val rootLoc = IntArray(2)
                            rootView.getLocationInWindow(rootLoc)
                            val surfaceLoc = IntArray(2)
                            surfaceView.getLocationInWindow(surfaceLoc)
                            val relX = surfaceLoc[0] - rootLoc[0]
                            val relY = surfaceLoc[1] - rootLoc[1]

                            // 4. Combina i due bitmap su una canvas finale con sfondo nero (per le barre esterne)
                            val combinedBitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                            val comboCanvas = Canvas(combinedBitmap)
                            comboCanvas.drawColor(android.graphics.Color.BLACK)

                            // Disegna prima il video catturato nella sua posizione
                            val srcRect = Rect(0, 0, sw, sh)
                            val destRect = Rect(relX, relY, relX + sw, relY + sh)
                            comboCanvas.drawBitmap(videoBitmap, srcRect, destRect, null)

                            // Disegna sopra la UI (che contiene i pulsanti intatti con sfondo trasparente)
                            comboCanvas.drawBitmap(uiBitmap, 0f, 0f, null)

                            // Ricicla i bitmap intermedi per liberare memoria
                            videoBitmap.recycle()
                            uiBitmap.recycle()

                            saveAndShare(context, combinedBitmap, callback)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            captureFromWindow(context, rootView, callback)
                        }
                    } else {
                        captureFromWindow(context, rootView, callback)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } else {
            captureFromWindow(context, rootView, callback)
        }
    }

    private fun captureFromWindow(context: android.content.Context, view: View, callback: (Boolean) -> Unit) {
        val activity = context as? Activity ?: run { callback(false); return }
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val srcRect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)

        PixelCopy.request(
            activity.window,
            srcRect,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) saveAndShare(context, bitmap, callback)
                else callback(false)
            },
            Handler(Looper.getMainLooper())
        )
    }

    private fun saveAndShare(context: android.content.Context, bitmap: Bitmap, callback: (Boolean) -> Unit) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "videodelay_$timestamp.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VideoDelay")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("URI MediaStore null")

            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            shareImage(context, uri)
            callback(true)
        } catch (e: Exception) {
            e.printStackTrace()
            callback(false)
        }
    }

    private fun shareImage(context: android.content.Context, uri: Uri) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Condividi screenshot"))
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findSurfaceView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    fun captureForEditing(context: android.content.Context, rootView: View, callback: (String?) -> Unit) {
        val surfaceView = findSurfaceView(rootView)

        val onBitmapCaptured = { bitmap: Bitmap ->
            try {
                val tempFile = File(context.cacheDir, "temp_screenshot_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openOutputStream(Uri.fromFile(tempFile))?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bitmap.recycle()
                callback(tempFile.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }

        if (surfaceView != null && surfaceView.holder?.surface?.isValid == true) {
            val sw = surfaceView.width.coerceAtLeast(1)
            val sh = surfaceView.height.coerceAtLeast(1)
            val videoBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)

            PixelCopy.request(
                surfaceView,
                videoBitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        try {
                            val rw = rootView.width.coerceAtLeast(1)
                            val rh = rootView.height.coerceAtLeast(1)

                            val oldRootBg = rootView.background
                            rootView.background = null

                            val playerView = rootView.findViewById<View>(it.videodelay.app.R.id.player_view)
                            val oldPlayerBg = playerView?.background
                            playerView?.background = null

                            val oldSurfaceVisibility = surfaceView.visibility
                            surfaceView.visibility = View.INVISIBLE

                            val uiBitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                            val uiCanvas = Canvas(uiBitmap)
                            rootView.draw(uiCanvas)

                            rootView.background = oldRootBg
                            playerView?.background = oldPlayerBg
                            surfaceView.visibility = oldSurfaceVisibility

                            val rootLoc = IntArray(2)
                            rootView.getLocationInWindow(rootLoc)
                            val surfaceLoc = IntArray(2)
                            surfaceView.getLocationInWindow(surfaceLoc)
                            val relX = surfaceLoc[0] - rootLoc[0]
                            val relY = surfaceLoc[1] - rootLoc[1]

                            val combinedBitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                            val comboCanvas = Canvas(combinedBitmap)
                            comboCanvas.drawColor(android.graphics.Color.BLACK)

                            val srcRect = Rect(0, 0, sw, sh)
                            val destRect = Rect(relX, relY, relX + sw, relY + sh)
                            comboCanvas.drawBitmap(videoBitmap, srcRect, destRect, null)
                            comboCanvas.drawBitmap(uiBitmap, 0f, 0f, null)

                            videoBitmap.recycle()
                            uiBitmap.recycle()

                            onBitmapCaptured(combinedBitmap)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            captureFromWindowForEditing(context, rootView, onBitmapCaptured)
                        }
                    } else {
                        captureFromWindowForEditing(context, rootView, onBitmapCaptured)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } else {
            captureFromWindowForEditing(context, rootView, onBitmapCaptured)
        }
    }

    private fun captureFromWindowForEditing(context: android.content.Context, view: View, callback: (Bitmap) -> Unit) {
        val activity = context as? Activity ?: run { return }
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val srcRect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)

        PixelCopy.request(
            activity.window,
            srcRect,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) callback(bitmap)
            },
            Handler(Looper.getMainLooper())
        )
    }

    fun getSavedScreenshots(context: android.content.Context): List<Uri> {
        val list = ArrayList<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Pictures/VideoDelay%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    list.add(contentUri)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
