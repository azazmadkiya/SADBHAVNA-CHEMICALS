package com.example

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import java.io.File

object DownloadHelper {
    const val DOWNLOAD_SUBFOLDER = "SADBHAVNA CHEMICALS"
    private const val TAG = "DownloadHelper"

    /**
     * Ensures that the "SADBHAVNA CHEMICALS" directory exists inside the public Downloads folder.
     */
    fun getAppDownloadsDir(): File {
        val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(pubDownloads, DOWNLOAD_SUBFOLDER)
        if (!appDir.exists()) {
            try {
                appDir.mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "Could not create download folder: ${e.message}")
            }
        }
        return appDir
    }

    /**
     * Sanitizes file names to ensure valid filesystem characters and clean extension.
     */
    fun sanitizeFileName(rawFileName: String): String {
        var clean = rawFileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (clean.isBlank()) {
            clean = "document_${System.currentTimeMillis()}"
        }
        return clean
    }

    /**
     * Deduces a reasonable filename based on URL, Content-Disposition, and MIME type.
     */
    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        var guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (guessed.equals("downloadfile.bin", ignoreCase = true) || guessed.endsWith(".bin")) {
            val cleanUrl = url.substringBefore('?').substringBefore('#')
            val candidate = cleanUrl.substringAfterLast('/')
            if (candidate.isNotBlank() && candidate.contains('.')) {
                guessed = candidate
            } else if (!mimeType.isNullOrBlank()) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "pdf"
                guessed = "file_${System.currentTimeMillis()}.$ext"
            }
        }
        return sanitizeFileName(guessed)
    }

    /**
     * Enqueues an HTTP/HTTPS download request into Android's DownloadManager.
     * Target directory: Downloads/SADBHAVNA CHEMICALS/
     */
    fun enqueueDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): Pair<Long, String>? {
        return try {
            getAppDownloadsDir()
            val fileName = guessFileName(url, contentDisposition, mimeType)
            val subPath = "$DOWNLOAD_SUBFOLDER/$fileName"

            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri).apply {
                val resolvedMime = mimeType ?: getMimeTypeFromFileName(fileName)
                if (!resolvedMime.isNullOrBlank()) {
                    setMimeType(resolvedMime)
                }

                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                if (!userAgent.isNullOrEmpty()) {
                    addRequestHeader("User-Agent", userAgent)
                }

                setTitle(fileName)
                setDescription("Downloading to Downloads/$DOWNLOAD_SUBFOLDER")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, subPath)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            Pair(downloadId, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download", e)
            null
        }
    }

    /**
     * Saves a Base64 data: URI to Downloads/SADBHAVNA CHEMICALS/.
     */
    fun saveBase64Data(
        context: Context,
        dataUriOrBase64: String,
        suggestedFileName: String?,
        explicitMimeType: String?
    ): Pair<File?, String?> {
        return try {
            var mime = explicitMimeType
            var base64Content = dataUriOrBase64

            if (dataUriOrBase64.startsWith("data:")) {
                val commaIndex = dataUriOrBase64.indexOf(',')
                if (commaIndex != -1) {
                    val header = dataUriOrBase64.substring(5, commaIndex)
                    base64Content = dataUriOrBase64.substring(commaIndex + 1)
                    if (header.contains(";")) {
                        mime = header.substringBefore(';')
                    } else if (header.isNotBlank()) {
                        mime = header
                    }
                }
            }

            val ext = if (!mime.isNullOrBlank()) {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "pdf"
            } else {
                "pdf"
            }

            val cleanName = if (!suggestedFileName.isNullOrBlank()) {
                sanitizeFileName(suggestedFileName)
            } else {
                "document_${System.currentTimeMillis()}.$ext"
            }

            val bytes = Base64.decode(base64Content, Base64.DEFAULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                    if (!mime.isNullOrBlank()) {
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    }
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(bytes)
                    }
                }
            }

            // Also keep standard local copy in app folder for quick direct file open
            val targetFolder = getAppDownloadsDir()
            val targetFile = File(targetFolder, cleanName)
            targetFile.outputStream().use { stream ->
                stream.write(bytes)
            }

            Pair(targetFile, cleanName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save base64 download", e)
            Pair(null, null)
        }
    }

    /**
     * Opens the public Downloads folder or downloads directory in the system Files app.
     */
    fun openDownloadsFolder(context: Context) {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path),
                        "*/*"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot launch downloads folder: ${e.message}")
            }
        }
    }

    /**
     * Opens a downloaded file using an external viewing app.
     */
    fun openFile(context: Context, file: File, mimeType: String?) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val resolvedMime = mimeType ?: getMimeTypeFromFileName(file.name) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Open file with"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file: ${e.message}", e)
            openDownloadsFolder(context)
        }
    }

    fun getMimeTypeFromFileName(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else {
            null
        }
    }
}
