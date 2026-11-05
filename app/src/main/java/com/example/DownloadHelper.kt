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
import java.net.URLDecoder

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
     * Scans the SADBHAVNA CHEMICALS directory and automatically renames any .bin files to .pdf
     * so that already downloaded documents immediately become valid PDF files on the device.
     */
    fun autoFixBinFiles(context: Context? = null, directory: File = getAppDownloadsDir()) {
        try {
            val files = directory.listFiles() ?: return
            for (file in files) {
                if (file.isFile && file.name.endsWith(".bin", ignoreCase = true)) {
                    val newName = file.name.substringBeforeLast(".") + ".pdf"
                    val targetFile = File(directory, newName)
                    if (!targetFile.exists()) {
                        val renamed = file.renameTo(targetFile)
                        if (renamed) {
                            Log.i(TAG, "Successfully converted ${file.name} to ${targetFile.name}")
                            if (context != null) {
                                android.media.MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(targetFile.absolutePath),
                                    arrayOf("application/pdf"),
                                    null
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in autoFixBinFiles: ${e.message}")
        }
    }

    /**
     * Sanitizes file names to ensure valid filesystem characters and clean extension.
     * Preserves original filename without modifications or prefixes.
     */
    fun sanitizeFileName(rawFileName: String): String {
        var clean = rawFileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (clean.isBlank()) {
            clean = "Document_${System.currentTimeMillis()}.pdf"
        }
        // Never allow .bin as final extension, convert to .pdf preserving the name
        if (clean.endsWith(".bin", ignoreCase = true)) {
            clean = clean.substringBeforeLast(".") + ".pdf"
        }
        return clean
    }

    fun cleanFileName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (name.isBlank()) return ""
        if (name.endsWith(".bin", ignoreCase = true)) {
            name = name.substringBeforeLast(".") + ".pdf"
        }
        return name
    }

    /**
     * Robustly extracts original filename from Content-Disposition header supporting UTF-8 and standard syntax.
     */
    fun extractFileNameFromContentDisposition(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        try {
            // 1. Try RFC 5987 / UTF-8 filename*=UTF-8''...
            val utf8Match = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (utf8Match != null) {
                val encoded = utf8Match.groupValues[1].trim()
                val decoded = try { URLDecoder.decode(encoded, "UTF-8").trim() } catch (_: Exception) { encoded }
                if (decoded.isNotBlank()) return cleanFileName(decoded)
            }
            // 2. Try standard filename="..." with double quotes
            val doubleQuotedMatch = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (doubleQuotedMatch != null) {
                val candidate = doubleQuotedMatch.groupValues[1].trim()
                if (candidate.isNotBlank()) return cleanFileName(candidate)
            }
            // 3. Try standard filename='...' with single quotes
            val singleQuotedMatch = Regex("""filename\s*=\s*'([^']+)'""", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (singleQuotedMatch != null) {
                val candidate = singleQuotedMatch.groupValues[1].trim()
                if (candidate.isNotBlank()) return cleanFileName(candidate)
            }
            // 4. Try unquoted filename=something.ext
            val unquotedMatch = Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (unquotedMatch != null) {
                val candidate = unquotedMatch.groupValues[1].trim().trim('"', '\'')
                if (candidate.isNotBlank()) return cleanFileName(candidate)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Content-Disposition: ${e.message}")
        }
        return null
    }

    /**
     * Resolves the ORIGINAL filename of the download, ensuring that original filenames
     * (such as 'GST Certificate.pdf', 'Udhyam Certificate.pdf', 'Bank Details.pdf')
     * are never modified or replaced with generic names. If a file was named .bin,
     * only the extension is corrected to .pdf.
     */
    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        // 1. Check Content-Disposition first (highest fidelity for real filename)
        var resolvedName: String? = extractFileNameFromContentDisposition(contentDisposition)

        // 2. If Content-Disposition didn't provide a valid name, try URLUtil
        if (resolvedName.isNullOrBlank()) {
            val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (!guessed.isNullOrBlank() && !guessed.startsWith("downloadfile", ignoreCase = true)) {
                resolvedName = cleanFileName(guessed)
            }
        }

        // 3. If still blank or generic, check URL path
        if (resolvedName.isNullOrBlank() || resolvedName.startsWith("downloadfile", ignoreCase = true)) {
            val cleanUrl = url.substringBefore('?').substringBefore('#')
            val pathCandidate = cleanUrl.substringAfterLast('/').trim()
            if (pathCandidate.isNotBlank() &&
                pathCandidate.contains('.') &&
                !pathCandidate.startsWith("downloadfile", ignoreCase = true) &&
                !pathCandidate.equals("download", ignoreCase = true)
            ) {
                val decoded = try { URLDecoder.decode(pathCandidate, "UTF-8").trim() } catch (_: Exception) { pathCandidate }
                resolvedName = cleanFileName(decoded)
            }
        }

        // 4. If still blank or generic, check query parameters (e.g. ?file=original_doc.pdf)
        if (resolvedName.isNullOrBlank() || resolvedName.startsWith("downloadfile", ignoreCase = true)) {
            val query = url.substringAfter('?', "")
            val paramMatch = Regex("""(?:file|filename|name|doc|pdf|title)=([^&]+)""", RegexOption.IGNORE_CASE).find(query)
            val queryCandidate = paramMatch?.groupValues?.get(1)?.let {
                try { URLDecoder.decode(it, "UTF-8").trim() } catch (_: Exception) { it.trim() }
            }
            if (!queryCandidate.isNullOrBlank()) {
                resolvedName = cleanFileName(queryCandidate)
            }
        }

        // 5. If we have a resolved original name, preserve it exactly!
        if (!resolvedName.isNullOrBlank() && !resolvedName.startsWith("downloadfile", ignoreCase = true)) {
            if (resolvedName.endsWith(".bin", ignoreCase = true)) {
                resolvedName = resolvedName.substringBeforeLast(".") + ".pdf"
            } else if (!resolvedName.contains(".")) {
                val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "pdf"
                val safeExt = if (ext.equals("bin", ignoreCase = true) || ext.isBlank()) "pdf" else ext
                resolvedName = "$resolvedName.$safeExt"
            }
            return sanitizeFileName(resolvedName)
        }

        // 6. Absolute last resort fallback only if no original name exists anywhere
        val fallbackExt = if (mimeType == "application/pdf" || mimeType.isNullOrBlank() || mimeType == "application/octet-stream") "pdf" else {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "pdf"
        }
        val safeExt = if (fallbackExt.equals("bin", ignoreCase = true) || fallbackExt.isBlank()) "pdf" else fallbackExt
        return "Document_${System.currentTimeMillis()}.$safeExt"
    }

    /**
     * Fast helper to fetch real server headers (Content-Disposition & Content-Type)
     * using HEAD or GET Range 0-0 so original filename is discovered even if WebView intercepted early.
     */
    fun fetchHeaders(url: String, userAgent: String?): Pair<String?, String?> {
        return try {
            val u = java.net.URL(url)
            val conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 3500
                readTimeout = 3500
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    setRequestProperty("Cookie", cookies)
                }
                if (!userAgent.isNullOrEmpty()) {
                    setRequestProperty("User-Agent", userAgent)
                }
            }
            conn.connect()
            var cd = conn.getHeaderField("Content-Disposition")
            var ct = conn.contentType
            val responseCode = conn.responseCode

            if ((cd.isNullOrBlank() && responseCode >= 400) || responseCode == java.net.HttpURLConnection.HTTP_BAD_METHOD) {
                conn.disconnect()
                val getConn = (u.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=0-0")
                    instanceFollowRedirects = true
                    connectTimeout = 3500
                    readTimeout = 3500
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
                    if (!userAgent.isNullOrEmpty()) setRequestProperty("User-Agent", userAgent)
                }
                getConn.connect()
                cd = getConn.getHeaderField("Content-Disposition")
                ct = getConn.contentType
                getConn.disconnect()
            } else {
                conn.disconnect()
            }
            Pair(cd, ct)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    /**
     * Enqueues an HTTP/HTTPS download request into Android's DownloadManager.
     * Enforces PDF MIME type and destination in Downloads/SADBHAVNA CHEMICALS/.
     */
    fun enqueueDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): Pair<Long, String>? {
        return try {
            val appDir = getAppDownloadsDir()
            autoFixBinFiles(context, appDir)

            val fileName = guessFileName(url, contentDisposition, mimeType)
            val subPath = "$DOWNLOAD_SUBFOLDER/$fileName"

            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri).apply {
                // Ensure PDF files always have application/pdf MIME type
                val resolvedMime = if (fileName.endsWith(".pdf", ignoreCase = true) ||
                    mimeType == "application/octet-stream" ||
                    mimeType == "binary/octet-stream" ||
                    mimeType.isNullOrBlank()
                ) {
                    "application/pdf"
                } else {
                    mimeType ?: getMimeTypeFromFileName(fileName) ?: "application/pdf"
                }

                setMimeType(resolvedMime)

                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                if (!userAgent.isNullOrEmpty()) {
                    addRequestHeader("User-Agent", userAgent)
                }

                setTitle(fileName)
                setDescription("Downloading PDF to Downloads/$DOWNLOAD_SUBFOLDER")
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
     * Guarantees PDF extension and valid MIME type.
     */
    fun saveBase64Data(
        context: Context,
        dataUriOrBase64: String,
        suggestedFileName: String?,
        explicitMimeType: String?
    ): Pair<File?, String?> {
        return try {
            autoFixBinFiles(context)
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

            if (mime.isNullOrBlank() || mime == "application/octet-stream") {
                mime = "application/pdf"
            }

            var ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "pdf"
            if (ext.equals("bin", ignoreCase = true) || ext.isBlank()) {
                ext = "pdf"
            }

            var cleanName = if (!suggestedFileName.isNullOrBlank()) {
                sanitizeFileName(suggestedFileName)
            } else {
                "Sadbhavna_Doc_${System.currentTimeMillis()}.$ext"
            }

            if (cleanName.endsWith(".bin", ignoreCase = true)) {
                cleanName = cleanName.substringBeforeLast(".") + ".pdf"
            }

            val bytes = Base64.decode(base64Content, Base64.DEFAULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
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
        autoFixBinFiles(context)
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
     * Opens a downloaded file using an external PDF / document viewer.
     */
    fun openFile(context: Context, file: File, mimeType: String?) {
        autoFixBinFiles(context)
        try {
            // If file ended with .bin, check if .pdf version exists
            val targetFile = if (file.name.endsWith(".bin", ignoreCase = true)) {
                val pdfVersion = File(file.parentFile, file.name.substringBeforeLast(".") + ".pdf")
                if (pdfVersion.exists()) pdfVersion else file
            } else {
                file
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )

            val resolvedMime = if (targetFile.name.endsWith(".pdf", ignoreCase = true)) {
                "application/pdf"
            } else {
                mimeType ?: getMimeTypeFromFileName(targetFile.name) ?: "application/pdf"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Open PDF with"))
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
            "application/pdf"
        }
    }
}
