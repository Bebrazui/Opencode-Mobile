package ai.opencode.mobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ai.opencode.mobile.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var termuxManager: TermuxManager
    private var backendReady = false
    private var serverUrl = ""
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Intent>
    private var folderPickerCallback: String? = null

    private val logLines = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val resultUri = data?.data
                val clipData = data?.clipData
                if (clipData != null) {
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                    fileChooserCallback?.onReceiveValue(uris.toTypedArray())
                } else if (resultUri != null) {
                    fileChooserCallback?.onReceiveValue(arrayOf(resultUri))
                } else {
                    fileChooserCallback?.onReceiveValue(null)
                }
            } else {
                fileChooserCallback?.onReceiveValue(null)
            }
            fileChooserCallback = null
        }

        folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val path = getPathFromUri(uri)
                    if (path != null && folderPickerCallback != null) {
                        val js = "window.__folderPickerResult('${path.replace("'", "\\'")}')"
                        runOnUiThread {
                            binding.webview.evaluateJavascript(js, null)
                        }
                    }
                }
            }
            folderPickerCallback = null
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        termuxManager = TermuxManager(this)

        termuxManager.onStatusChange = { status ->
            runOnUiThread {
                binding.statusText.text = status
            }
        }

        termuxManager.onLogLine = { line ->
            runOnUiThread {
                logLines.appendLine(line)
                binding.logText.text = logLines.toString()
                binding.logScroll.post {
                    binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }

        termuxManager.onStepUpdate = { step, status ->
            runOnUiThread {
                updateStepUI(step, status)
            }
        }

        termuxManager.onReady = {
            runOnUiThread {
                serverUrl = termuxManager.getServerUrl()
                loadWebView()
            }
        }

        termuxManager.onError = { error ->
            runOnUiThread {
                binding.statusText.text = "Error: $error"
                binding.retryBtn.visibility = View.VISIBLE
            }
        }

        binding.copyLogsBtn.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OpenCode logs", logLines.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
        }

        binding.retryBtn.setOnClickListener {
            logLines.clear()
            binding.logText.text = ""
            binding.retryBtn.visibility = View.GONE
            resetSteps()
            termuxManager.stop()
            startBackend()
        }

        setupWebView()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        val isFirst = termuxManager.isFirstLaunch()
        if (isFirst) {
            binding.splashSimple.visibility = View.VISIBLE
            binding.splashDetailed.visibility = View.GONE
        } else {
            binding.splashSimple.visibility = View.GONE
            binding.splashDetailed.visibility = View.VISIBLE
        }

        startBackend()
    }

    private fun updateStepUI(step: String, status: String) {
        val mappedStep = when (step) {
            "setup" -> "extract"
            "shell" -> "bun"
            else -> step
        }
        when (mappedStep) {
            "extract" -> {
                when (status) {
                    "running" -> {
                        binding.stepExtractProgress.visibility = View.VISIBLE
                        binding.stepExtractIcon.visibility = View.GONE
                        binding.stepExtractStatus.text = "..."
                        binding.stepExtractStatus.setTextColor(0xFF58A6FF.toInt())
                    }
                    "done" -> {
                        binding.stepExtractProgress.visibility = View.GONE
                        binding.stepExtractIcon.visibility = View.VISIBLE
                        binding.stepExtractIcon.text = "\u2713"
                        binding.stepExtractIcon.setTextColor(0xFF3FB950.toInt())
                        binding.stepExtractStatus.text = "done"
                        binding.stepExtractStatus.setTextColor(0xFF3FB950.toInt())
                    }
                    "error" -> {
                        binding.stepExtractProgress.visibility = View.GONE
                        binding.stepExtractIcon.visibility = View.VISIBLE
                        binding.stepExtractIcon.text = "\u2717"
                        binding.stepExtractIcon.setTextColor(0xFFF85149.toInt())
                        binding.stepExtractStatus.text = "failed"
                        binding.stepExtractStatus.setTextColor(0xFFF85149.toInt())
                    }
                }
            }
            "bun" -> {
                when (status) {
                    "running" -> {
                        binding.stepBunProgress.visibility = View.VISIBLE
                        binding.stepBunIcon.visibility = View.GONE
                        binding.stepBunStatus.text = "..."
                        binding.stepBunStatus.setTextColor(0xFF58A6FF.toInt())
                    }
                    "done" -> {
                        binding.stepBunProgress.visibility = View.GONE
                        binding.stepBunIcon.visibility = View.VISIBLE
                        binding.stepBunIcon.text = "\u2713"
                        binding.stepBunIcon.setTextColor(0xFF3FB950.toInt())
                        binding.stepBunStatus.text = "done"
                        binding.stepBunStatus.setTextColor(0xFF3FB950.toInt())
                    }
                    "error" -> {
                        binding.stepBunProgress.visibility = View.GONE
                        binding.stepBunIcon.visibility = View.VISIBLE
                        binding.stepBunIcon.text = "\u2717"
                        binding.stepBunIcon.setTextColor(0xFFF85149.toInt())
                        binding.stepBunStatus.text = "failed"
                        binding.stepBunStatus.setTextColor(0xFFF85149.toInt())
                    }
                }
            }
            "opencode" -> {
                when (status) {
                    "running" -> {
                        binding.stepOpencodeProgress.visibility = View.VISIBLE
                        binding.stepOpencodeIcon.visibility = View.GONE
                        binding.stepOpencodeStatus.text = "..."
                        binding.stepOpencodeStatus.setTextColor(0xFF58A6FF.toInt())
                    }
                    "done" -> {
                        binding.stepOpencodeProgress.visibility = View.GONE
                        binding.stepOpencodeIcon.visibility = View.VISIBLE
                        binding.stepOpencodeIcon.text = "\u2713"
                        binding.stepOpencodeIcon.setTextColor(0xFF3FB950.toInt())
                        binding.stepOpencodeStatus.text = "done"
                        binding.stepOpencodeStatus.setTextColor(0xFF3FB950.toInt())
                    }
                    "error" -> {
                        binding.stepOpencodeProgress.visibility = View.GONE
                        binding.stepOpencodeIcon.visibility = View.VISIBLE
                        binding.stepOpencodeIcon.text = "\u2717"
                        binding.stepOpencodeIcon.setTextColor(0xFFF85149.toInt())
                        binding.stepOpencodeStatus.text = "failed"
                        binding.stepOpencodeStatus.setTextColor(0xFFF85149.toInt())
                    }
                }
            }
            "install" -> {
                when (status) {
                    "running" -> {
                        binding.stepInstallProgress.visibility = View.VISIBLE
                        binding.stepInstallIcon.visibility = View.GONE
                        binding.stepInstallStatus.text = "..."
                        binding.stepInstallStatus.setTextColor(0xFF58A6FF.toInt())
                    }
                    "done" -> {
                        binding.stepInstallProgress.visibility = View.GONE
                        binding.stepInstallIcon.visibility = View.VISIBLE
                        binding.stepInstallIcon.text = "\u2713"
                        binding.stepInstallIcon.setTextColor(0xFF3FB950.toInt())
                        binding.stepInstallStatus.text = "done"
                        binding.stepInstallStatus.setTextColor(0xFF3FB950.toInt())
                    }
                    "error" -> {
                        binding.stepInstallProgress.visibility = View.GONE
                        binding.stepInstallIcon.visibility = View.VISIBLE
                        binding.stepInstallIcon.text = "\u2717"
                        binding.stepInstallIcon.setTextColor(0xFFF85149.toInt())
                        binding.stepInstallStatus.text = "failed"
                        binding.stepInstallStatus.setTextColor(0xFFF85149.toInt())
                    }
                }
            }
            "server" -> {
                when (status) {
                    "running" -> {
                        binding.stepServerProgress.visibility = View.VISIBLE
                        binding.stepServerIcon.visibility = View.GONE
                        binding.stepServerStatus.text = "..."
                        binding.stepServerStatus.setTextColor(0xFF58A6FF.toInt())
                    }
                    "done" -> {
                        binding.stepServerProgress.visibility = View.GONE
                        binding.stepServerIcon.visibility = View.VISIBLE
                        binding.stepServerIcon.text = "\u2713"
                        binding.stepServerIcon.setTextColor(0xFF3FB950.toInt())
                        binding.stepServerStatus.text = "done"
                        binding.stepServerStatus.setTextColor(0xFF3FB950.toInt())
                    }
                    "error" -> {
                        binding.stepServerProgress.visibility = View.GONE
                        binding.stepServerIcon.visibility = View.VISIBLE
                        binding.stepServerIcon.text = "\u2717"
                        binding.stepServerIcon.setTextColor(0xFFF85149.toInt())
                        binding.stepServerStatus.text = "failed"
                        binding.stepServerStatus.setTextColor(0xFFF85149.toInt())
                    }
                }
            }
        }
    }

    private fun resetSteps() {
        val steps = listOf(
            binding.stepExtractProgress, binding.stepExtractIcon,
            binding.stepBunProgress, binding.stepBunIcon,
            binding.stepOpencodeProgress, binding.stepOpencodeIcon,
            binding.stepInstallProgress, binding.stepInstallIcon,
            binding.stepServerProgress, binding.stepServerIcon
        )
        steps.forEach { it.visibility = View.GONE }

        binding.stepExtractStatus.text = "pending"
        binding.stepExtractStatus.setTextColor(0xFF484F58.toInt())
        binding.stepBunStatus.text = ""
        binding.stepBunStatus.setTextColor(0xFF484F58.toInt())
        binding.stepOpencodeStatus.text = ""
        binding.stepOpencodeStatus.setTextColor(0xFF484F58.toInt())
        binding.stepInstallStatus.text = ""
        binding.stepInstallStatus.setTextColor(0xFF484F58.toInt())
        binding.stepServerStatus.text = ""
        binding.stepServerStatus.setTextColor(0xFF484F58.toInt())
    }

    private fun startBackend() {
        val projectPath = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        termuxManager.start(projectPath)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webview.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Override directory picker for Android
                    val js = """
                        (function() {
                            window.__folderPickerResult = null;
                            window.openFolderPicker = function() {
                                return new Promise(function(resolve) {
                                    window.__folderPickerResult = function(path) {
                                        resolve(path);
                                    };
                                    if (window.AndroidBridge) {
                                        window.AndroidBridge.openFolderPicker('folder');
                                    }
                                });
                            };
                            
                            // Override the directory picker dialog
                            var observer = new MutationObserver(function(mutations) {
                                mutations.forEach(function(mutation) {
                                    mutation.addedNodes.forEach(function(node) {
                                        if (node.nodeType === 1) {
                                            // Look for directory picker dialog
                                            var dialogs = node.querySelectorAll ? node.querySelectorAll('[role="dialog"], [data-component="dialog"]') : [];
                                            dialogs.forEach(function(dialog) {
                                                // Check if it's a directory picker
                                                var text = dialog.textContent || '';
                                                if (text.includes('Open Project') || text.includes('Select') || text.includes('directory') || text.includes('folder')) {
                                                    // Find the input field and add a button to use Android picker
                                                    var inputs = dialog.querySelectorAll('input[type="text"], input:not([type])');
                                                    inputs.forEach(function(input) {
                                                        if (!input.dataset.androidPickerAdded) {
                                                            input.dataset.androidPickerAdded = 'true';
                                                            var btn = document.createElement('button');
                                                            btn.textContent = '📱 Pick folder';
                                                            btn.style.cssText = 'margin-left: 8px; padding: 4px 8px; background: #3b5cf6; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 12px;';
                                                            btn.onclick = function() {
                                                                window.openFolderPicker().then(function(path) {
                                                                    if (path) {
                                                                        input.value = path;
                                                                        input.dispatchEvent(new Event('input', { bubbles: true }));
                                                                        input.dispatchEvent(new Event('change', { bubbles: true }));
                                                                    }
                                                                });
                                                            };
                                                            input.parentNode.insertBefore(btn, input.nextSibling);
                                                        }
                                                    });
                                                }
                                            });
                                        }
                                    });
                                });
                            });
                            observer.observe(document.body, { childList: true, subtree: true });
                            
                            // Monitor for task completion
                            var lastThinkingState = false;
                            var taskMonitor = setInterval(function() {
                                var thinkingEl = document.querySelector('[data-component="thinking"], .thinking, [class*="loading"], [class*="spinner"]');
                                var isThinking = thinkingEl !== null;
                                
                                if (lastThinkingState && !isThinking) {
                                    // Task just completed
                                    if (window.AndroidBridge) {
                                        window.AndroidBridge.notifyTaskComplete('OpenCode', 'Task completed');
                                    }
                                }
                                lastThinkingState = isThinking;
                            }, 1000);
                            
                            // Also monitor for new assistant messages
                            var messageObserver = new MutationObserver(function(mutations) {
                                mutations.forEach(function(mutation) {
                                    mutation.addedNodes.forEach(function(node) {
                                        if (node.nodeType === 1) {
                                            var role = node.getAttribute('data-role') || node.getAttribute('role');
                                            if (role === 'assistant') {
                                                // Check if this is a complete message (not streaming)
                                                setTimeout(function() {
                                                    var streamingEl = node.querySelector('[data-streaming="true"], .streaming');
                                                    if (!streamingEl && window.AndroidBridge) {
                                                        window.AndroidBridge.notifyTaskComplete('OpenCode', 'Response received');
                                                    }
                                                }, 2000);
                                            }
                                        }
                                    });
                                });
                            });
                            messageObserver.observe(document.body, { childList: true, subtree: true });
                        })();
                    """.trimIndent()
                    evaluateJavascript(js, null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        view?.postDelayed({
                            view.reload()
                        }, 3000)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Block external URLs
                    if (!url.startsWith(serverUrl) && !url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
                        return true
                    }
                    return false
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return false
                    if (!url.startsWith(serverUrl) && !url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
                        return true
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    android.util.Log.d("WebView", consoleMessage?.message() ?: "")
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }

                    val chooserIntent = Intent.createChooser(intent, "Select files")
                    fileChooserLauncher.launch(chooserIntent)
                    return true
                }
            }

            addJavascriptInterface(WebBridge(), "AndroidBridge")
        }
    }

    private inner class WebBridge {
        @JavascriptInterface
        fun openFolderPicker(callbackId: String) {
            folderPickerCallback = callbackId
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            folderPickerLauncher.launch(intent)
        }

        @JavascriptInterface
        fun notifyTaskComplete(title: String, text: String) {
            termuxManager.sendTaskCompleteNotification(title, text)
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        // Handle SAF DocumentTree URI
        if (uri.toString().startsWith("content://com.android.externalstorage.documents/tree/")) {
            val docId = uri.lastPathSegment ?: return null
            return "/storage/${docId.replace(":", "/")}"
        }
        // Handle regular file URIs
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                if (idx >= 0) {
                    val docId = it.getString(idx)
                    if (docId != null) {
                        if (docId.startsWith("primary:")) {
                            return "/storage/emulated/0/${docId.removePrefix("primary:")}"
                        }
                        return "/storage/$docId"
                    }
                }
            }
        }
        return uri.path
    }

    private fun loadWebView() {
        binding.splashSimple.visibility = View.GONE
        binding.splashDetailed.visibility = View.GONE
        binding.webview.visibility = View.VISIBLE
        binding.webview.loadUrl(serverUrl)
    }

    override fun onResume() {
        super.onResume()
        if (termuxManager.isServerReady()) {
            termuxManager.updateServiceStatus("Backend running")
        }
    }

    override fun onPause() {
        super.onPause()
        if (termuxManager.isServerReady()) {
            termuxManager.updateServiceStatus("Running in background")
        }
    }

    override fun onBackPressed() {
        if (binding.webview.visibility == View.VISIBLE && binding.webview.canGoBack()) {
            binding.webview.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        termuxManager.destroy()
        binding.webview.destroy()
        super.onDestroy()
    }
}
