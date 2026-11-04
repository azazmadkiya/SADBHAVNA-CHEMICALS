package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                WebViewScreen(url = "https://cutt.ly/sadbhavnachemicals")
            }
        }
    }
}

suspend fun checkInternetConnectivity(context: Context): Boolean = withContext(Dispatchers.IO) {
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext false
        val activeNetwork = cm.activeNetwork ?: return@withContext false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return@withContext false

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return@withContext false
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return@withContext true
        }

        // Quick fallback check if validation is pending
        Socket().use { socket ->
            socket.connect(InetSocketAddress("8.8.8.8", 53), 1500)
            true
        }
    } catch (_: Exception) {
        false
    }
}

data class PendingDownload(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?
)

class BlobJsInterface(
    private val onBlobReceived: (base64Data: String, fileName: String, mimeType: String) -> Unit
) {
    @JavascriptInterface
    fun processBlob(base64Data: String, fileName: String, mimeType: String) {
        onBlobReceived(base64Data, fileName, mimeType)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isRefreshing by remember { mutableStateOf(false) }
    var isCheckingConnection by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var hasError by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }

    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val pullToRefreshState = rememberPullToRefreshState()

    // File picker launcher for WebChromeClient.onShowFileChooser
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val clipData = result.data?.clipData
        val results: Array<Uri>? = when {
            clipData != null -> Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            uri != null -> arrayOf(uri)
            else -> null
        }
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    // Storage permission launcher for legacy Android versions (<= API 28)
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingDownload?.let { pd ->
                val result = DownloadHelper.enqueueDownload(
                    context = context,
                    url = pd.url,
                    userAgent = pd.userAgent,
                    contentDisposition = pd.contentDisposition,
                    mimeType = pd.mimeType
                )
                if (result != null) {
                    val (_, fileName) = result
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Downloading $fileName to Downloads/SADBHAVNA CHEMICALS...",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = "Storage permission required to download on this device.",
                    duration = SnackbarDuration.Short
                )
            }
        }
        pendingDownload = null
    }

    // Notification permission launcher for Android 13+ (API 33+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Download continues regardless of notification preference
    }

    // Function to initiate download
    fun startDownload(
        downloadUrl: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (downloadUrl.startsWith("blob:")) {
            val suggestedFileName = DownloadHelper.guessFileName(downloadUrl, contentDisposition, mimeType)
            val js = """
                (function() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$downloadUrl', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function(e) {
                            if (this.status === 200) {
                                var blob = this.response;
                                var reader = new FileReader();
                                reader.readAsDataURL(blob);
                                reader.onloadend = function() {
                                    AndroidBlobDownloader.processBlob(reader.result, '$suggestedFileName', '$mimeType');
                                }
                            }
                        };
                        xhr.send();
                    } catch (err) {}
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
            return
        }

        if (downloadUrl.startsWith("data:")) {
            val (savedFile, savedName) = DownloadHelper.saveBase64Data(
                context = context,
                dataUriOrBase64 = downloadUrl,
                suggestedFileName = null,
                explicitMimeType = mimeType
            )
            coroutineScope.launch {
                val label = savedName ?: "file"
                val snackbarResult = snackbarHostState.showSnackbar(
                    message = "Saved $label into Downloads/SADBHAVNA CHEMICALS",
                    actionLabel = "Open",
                    duration = SnackbarDuration.Long
                )
                if (snackbarResult == SnackbarResult.ActionPerformed) {
                    if (savedFile != null && savedFile.exists()) {
                        DownloadHelper.openFile(context, savedFile, mimeType)
                    } else {
                        DownloadHelper.openDownloadsFolder(context)
                    }
                }
            }
            return
        }

        // Android 13+ notification permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Legacy Android <= 9 storage permission check
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = PendingDownload(downloadUrl, userAgent, contentDisposition, mimeType)
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }

        val result = DownloadHelper.enqueueDownload(
            context = context,
            url = downloadUrl,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType
        )

        if (result != null) {
            val (_, fileName) = result
            coroutineScope.launch {
                val actionResult = snackbarHostState.showSnackbar(
                    message = "Downloading $fileName into Downloads/SADBHAVNA CHEMICALS",
                    actionLabel = "View Folder",
                    duration = SnackbarDuration.Short
                )
                if (actionResult == SnackbarResult.ActionPerformed) {
                    DownloadHelper.openDownloadsFolder(context)
                }
            }
        }
    }

    // Register broadcast receiver for completed downloads
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (downloadId != -1L) {
                        try {
                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            val query = DownloadManager.Query().setFilterById(downloadId)
                            val cursor = dm.query(query)
                            if (cursor != null && cursor.moveToFirst()) {
                                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                                val status = if (statusIndex != -1) cursor.getInt(statusIndex) else -1
                                val title = if (titleIndex != -1) cursor.getString(titleIndex) else "File"
                                cursor.close()

                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    coroutineScope.launch {
                                        val snackbarResult = snackbarHostState.showSnackbar(
                                            message = "Downloaded $title to Downloads/SADBHAVNA CHEMICALS",
                                            actionLabel = "Open",
                                            duration = SnackbarDuration.Long
                                        )
                                        if (snackbarResult == SnackbarResult.ActionPerformed) {
                                            val dir = DownloadHelper.getAppDownloadsDir()
                                            val file = File(dir, title)
                                            if (file.exists()) {
                                                DownloadHelper.openFile(context, file, null)
                                            } else {
                                                DownloadHelper.openDownloadsFolder(context)
                                            }
                                        }
                                    }
                                }
                            } else {
                                cursor?.close()
                            }
                        } catch (e: Exception) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Download complete. Check Downloads/SADBHAVNA CHEMICALS",
                                    actionLabel = "Open Folder",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    // Check connectivity on initial launch
    LaunchedEffect(Unit) {
        val connected = checkInternetConnectivity(context)
        if (!connected) {
            hasError = true
            isLoading = false
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
        }
    }

    fun triggerRefresh() {
        if (isCheckingConnection) return
        isCheckingConnection = true

        coroutineScope.launch {
            val connected = checkInternetConnectivity(context)
            isCheckingConnection = false
            isRefreshing = false

            if (connected) {
                hasError = false
                isLoading = true
                webView?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                webView?.loadUrl(url)
            } else {
                hasError = true
                isLoading = false
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                snackbarHostState.showSnackbar(
                    message = "Internet connection is not available.",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    BackHandler(enabled = canGoBack && !hasError) {
        webView?.goBack()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                triggerRefresh()
            },
            state = pullToRefreshState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Persistent Android WebView
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (hasError) 0f else 1f)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.loadsImagesAutomatically = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true

                                // Javascript interface for blob downloads
                                addJavascriptInterface(
                                    BlobJsInterface { base64Data, fileName, mime ->
                                        val (savedFile, savedName) = DownloadHelper.saveBase64Data(
                                            context = ctx,
                                            dataUriOrBase64 = base64Data,
                                            suggestedFileName = fileName,
                                            explicitMimeType = mime
                                        )
                                        coroutineScope.launch {
                                            val label = savedName ?: fileName
                                            val snackResult = snackbarHostState.showSnackbar(
                                                message = "Saved $label into Downloads/SADBHAVNA CHEMICALS",
                                                actionLabel = "Open",
                                                duration = SnackbarDuration.Long
                                            )
                                            if (snackResult == SnackbarResult.ActionPerformed) {
                                                if (savedFile != null && savedFile.exists()) {
                                                    DownloadHelper.openFile(ctx, savedFile, mime)
                                                } else {
                                                    DownloadHelper.openDownloadsFolder(ctx)
                                                }
                                            }
                                        }
                                    },
                                    "AndroidBlobDownloader"
                                )

                                // WebView Download Listener
                                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                                    startDownload(
                                        downloadUrl = downloadUrl,
                                        userAgent = userAgent,
                                        contentDisposition = contentDisposition,
                                        mimeType = mimetype
                                    )
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress / 100f
                                        if (newProgress >= 80) {
                                            isLoading = false
                                        }
                                    }

                                    override fun onShowFileChooser(
                                        view: WebView?,
                                        filePathCallback: ValueCallback<Array<Uri>>?,
                                        fileChooserParams: FileChooserParams?
                                    ): Boolean {
                                        fileUploadCallback?.onReceiveValue(null)
                                        fileUploadCallback = filePathCallback
                                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            type = "*/*"
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                        }
                                        return try {
                                            fileChooserLauncher.launch(intent)
                                            true
                                        } catch (_: Exception) {
                                            fileUploadCallback?.onReceiveValue(null)
                                            fileUploadCallback = null
                                            false
                                        }
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val uri = request?.url ?: return false
                                        val urlString = uri.toString()
                                        val scheme = uri.scheme?.lowercase() ?: return false
                                        if (scheme != "http" && scheme != "https") {
                                            return try {
                                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                                view?.context?.startActivity(intent)
                                                true
                                            } catch (_: Exception) {
                                                true
                                            }
                                        }

                                        // Intercept downloadable file links (PDFs, docs, archives, sheets)
                                        val path = uri.path?.lowercase() ?: ""
                                        val isDirectDownload = path.endsWith(".pdf") ||
                                                path.endsWith(".docx") || path.endsWith(".doc") ||
                                                path.endsWith(".xlsx") || path.endsWith(".xls") ||
                                                path.endsWith(".csv") || path.endsWith(".zip") ||
                                                path.endsWith(".rar") || path.endsWith(".apk") ||
                                                path.endsWith(".7z") || path.endsWith(".tar") ||
                                                path.endsWith(".gz")

                                        if (isDirectDownload) {
                                            startDownload(
                                                downloadUrl = urlString,
                                                userAgent = settings.userAgentString,
                                                contentDisposition = null,
                                                mimeType = null
                                            )
                                            return true
                                        }

                                        return false
                                    }

                                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, pageUrl, favicon)
                                        if (pageUrl != null && pageUrl != "about:blank") {
                                            isLoading = true
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                        super.onPageFinished(view, pageUrl)
                                        if (pageUrl != null && pageUrl != "about:blank") {
                                            isLoading = false
                                            canGoBack = view?.canGoBack() ?: false
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            hasError = true
                                            isLoading = false
                                            view?.stopLoading()
                                            view?.loadUrl("about:blank")
                                        }
                                    }

                                    @Suppress("DEPRECATION")
                                    override fun onReceivedError(
                                        view: WebView?,
                                        errorCode: Int,
                                        description: String?,
                                        failingUrl: String?
                                    ) {
                                        super.onReceivedError(view, errorCode, description, failingUrl)
                                        hasError = true
                                        isLoading = false
                                        view?.stopLoading()
                                        view?.loadUrl("about:blank")
                                    }
                                }

                                loadUrl(url)
                                webView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Native App Loading Animation (Smooth crossfade into content)
                AnimatedVisibility(
                    visible = isLoading && !hasError,
                    enter = fadeIn(animationSpec = tween(150)),
                    exit = fadeOut(animationSpec = tween(450)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    NativeAppLoadingScreen(modifier = Modifier.fillMaxSize())
                }

                // Offline Error View
                if (hasError) {
                    OfflineErrorView(
                        isChecking = isCheckingConnection,
                        onRefresh = { triggerRefresh() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun OfflineErrorView(
    isChecking: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(100.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = "No Internet Connection",
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Internet Connection Not Available",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Please check your network settings and tap the refresh button to try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRefresh,
                enabled = !isChecking,
                modifier = Modifier
                    .testTag("refresh_button")
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Checking...",
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Refresh",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
fun NativeAppLoadingScreen(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF031020)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(160.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .graphicsLayer {
                                scaleX = pulseScale * 1.06f
                                scaleY = pulseScale * 1.06f
                                alpha = glowAlpha
                            }
                            .background(
                                color = Color(0xFF0284C7).copy(alpha = 0.25f),
                                shape = CircleShape
                            )
                    )

                    Surface(
                        shape = CircleShape,
                        color = Color.Transparent,
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .size(130.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_sadbhavna_logo),
                            contentDescription = "SADBHAVNA CHEMICALS",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "SADBHAVNA CHEMICALS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Quality Chemical Solutions",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF38BDF8),
                    letterSpacing = 0.8.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                NativePulsingDots(
                    color = Color(0xFF38BDF8),
                    modifier = Modifier.height(16.dp)
                )
            }
        }
    }
}

@Composable
fun NativePulsingDots(
    color: Color = Color(0xFF38BDF8),
    modifier: Modifier = Modifier
) {
    val dots = listOf(
        remember { Animatable(0f) },
        remember { Animatable(0f) },
        remember { Animatable(0f) }
    )

    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * 160L)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { dot ->
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer {
                        val scale = 0.5f + (dot.value * 0.7f)
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.35f + (dot.value * 0.65f)
                    }
                    .background(color, CircleShape)
            )
        }
    }
}


