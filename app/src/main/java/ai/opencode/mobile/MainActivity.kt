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
            settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val r = request ?: return null
                    val urlStr = r.url?.toString() ?: return null
                    val host = r.url?.host ?: return null
                    if (host != "127.0.0.1" && host != "localhost") return null
                    val path = r.url?.path ?: return null
                    if (path.startsWith("/api/") || path.startsWith("/session/")) {
                        android.util.Log.i("API_LOG", "REQ ${r.method} $urlStr accept=${r.requestHeaders?.get("Accept")}")
                        if (r.method?.uppercase() == "GET") {
                            if (path == "/api/session") return jsonResponse("""{"data":[],"cursor":{}}""")
                            return proxyApiGet(urlStr, r.requestHeaders)
                        }
                        return null
                    }
                    if (r.method?.uppercase() == "POST") {
                        android.util.Log.i("API_LOG", "REQ ${r.method} $urlStr")
                        return null
                    }
                    val webDir = java.io.File(filesDir, "web")
                    if (!webDir.exists()) return null
                    val rel = path.trimStart('/')
                    val file = if (rel.isEmpty() || rel == "index.html") java.io.File(webDir, "index.html")
                               else java.io.File(webDir, rel)
                    if (!file.exists() || !file.isFile) {
                        // SPA navigation fallback: serve index.html for extension-less routes.
                        // Missing static assets (js/css/font/...) are passed through so the server
                        // proxies them to app.opencode.ai (and WebView caches them for offline).
                        val isStaticAsset = rel.contains('.')
                        if (!isStaticAsset) {
                            val fallback = java.io.File(webDir, "index.html")
                            if (fallback.exists()) {
                                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension("html") ?: "text/html"
                                return WebResourceResponse(mime, "UTF-8", fallback.inputStream())
                            }
                        }
                        return null
                    }
                    val ext = file.extension.lowercase()
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        ?: if (ext == "js") "application/javascript" else "application/octet-stream"
                    return WebResourceResponse(mime, "UTF-8", file.inputStream())
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val js = """
(function() {
    if (window.__ocMobile) return;
    window.__ocMobile = true;

    // === ERROR INTERCEPTION ===
    window.onerror = function(msg, src, line, col, err) {
        console.error('[OC_ERR] ' + msg + ' at ' + (src||'') + ':' + line + ':' + col);
    };
    window.addEventListener('unhandledrejection', function(e) {
        var reason = e && e.reason;
        var detail = (reason && reason.stack) || (reason && reason.message) || String(reason);
        console.error('[OC_ERR_PROMISE] ' + detail);
    });

    // === OFFLINE: stub external fetches that block render ===
    var _origFetch = window.fetch;
    window.fetch = function(url, opts) {
        if (typeof url === 'string' && (url.indexOf('opencode.ai') !== -1 || url.indexOf('changelog') !== -1)) {
            return Promise.reject(new Error('offline'));
        }
        var urlStr = typeof url === 'string' ? url : (url && url.url) || '';
        return _origFetch.apply(this, arguments).catch(function(err) {
            console.error('[OC_FETCH_ERR] ' + (opts && opts.method || 'GET') + ' ' + urlStr + ' -> ' + (err.message || err));
            throw err;
        });
    };
    // Override notification icon to avoid external image load
    var _origNotify = window.Notification;
    if (_origNotify && _origNotify.permission) {
        var _origNotifyCtr = window.Notification;
        window.Notification = function(title, opts) {
            if (opts && opts.icon) opts.icon = undefined;
            return new _origNotifyCtr(title, opts);
        };
        window.Notification.permission = _origNotifyCtr.permission;
        window.Notification.requestPermission = _origNotifyCtr.requestPermission;
    }

    var css = document.createElement('style');
    css.id = 'oc-mobile-css';
    css.textContent = [
        '@media(max-width:639px){',
        // === DIALOG V2 (new layout) ===
        '[data-component="dialog-v2"][data-variant="settings"]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:100vw!important;margin:0!important;border-radius:0!important;z-index:99999}',
        '[data-component="dialog-v2"][data-variant="settings"] [data-slot="dialog-container"]{width:100%!important;height:100%!important;border-radius:0!important;padding:0!important}',
        '[data-component="dialog-v2"][data-variant="settings"] [data-slot="dialog-content"]{width:100%!important;height:100%!important;border-radius:0!important;padding:0!important;overflow:hidden!important}',
        '[data-component="dialog-v2"][data-variant="settings"] [data-slot="dialog-header"]{display:none!important}',
        '[data-component="dialog-v2"][data-variant="settings"] [data-slot="dialog-close-button"]{display:none!important}',
        '[data-component="dialog-v2"][data-variant="settings"] [data-slot="dialog-body"]{height:100%!important;padding:0!important;overflow:hidden!important;display:flex!important;flex-direction:column!important}',
        '[data-component="tabs-v2"][data-variant="settings"]{height:100%!important}',
        '[data-component="tabs-v2"][data-variant="settings"][data-orientation="vertical"] [data-slot="tabs-v2-list"]{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;z-index:2!important;display:flex!important;flex-direction:column!important;padding:8px 0!important;overflow-y:auto!important;background:var(--v2-background-bg-base,#111)!important;scrollbar-width:none!important}',
        '[data-component="tabs-v2"][data-variant="settings"][data-orientation="vertical"] [data-slot="tabs-v2-list"]::-webkit-scrollbar{display:none}',
        '[data-component="tabs-v2"][data-variant="settings"][data-slot="tabs-v2-section-title"]{padding:16px 20px 6px!important;font-size:12px!important;font-weight:600!important;text-transform:uppercase;letter-spacing:.5px;color:var(--v2-text-text-muted,#888)!important}',
        '[data-component="tabs-v2"][data-variant="settings"] [data-slot="tabs-v2-trigger-wrapper"]{padding:0!important;margin:0!important}',
        '[data-component="tabs-v2"][data-variant="settings"] [role="tab"]{width:100%!important;padding:14px 20px!important;font-size:16px!important;border-radius:0!important;border-bottom:.5px solid rgba(255,255,255,.06)!important;display:flex!important;align-items:center!important;gap:12px!important;justify-content:flex-start!important}',
        '[data-component="tabs-v2"][data-variant="settings"] [role="tab"]:active{background:rgba(255,255,255,.08)!important}',
        '[data-component="tabs-v2"][data-variant="settings"] [role="tab"] svg{width:20px!important;height:20px!important;color:var(--v2-icon-icon-muted,#888)!important;flex-shrink:0!important}',
        '[data-component="tabs-v2"][data-variant="settings"][data-orientation="vertical"] [data-slot="tabs-v2-content"]{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;z-index:3!important;overflow-y:auto!important;background:var(--v2-background-bg-base,#111)!important;scrollbar-width:none!important;padding:0!important}',
        '[data-component="tabs-v2"][data-variant="settings"][data-orientation="vertical"] [data-slot="tabs-v2-content"][data-selected]{display:flex!important;flex-direction:column!important}',
        // === DIALOG v1 (old layout) ===
        '[data-component="dialog"][data-size="x-large"]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:100vw!important;margin:0!important;border-radius:0!important;z-index:99999}',
        '[data-component="dialog"][data-size="x-large"] [data-slot="dialog-container"]{width:100%!important;height:100%!important;border-radius:0!important;padding:0!important}',
        '[data-component="dialog"][data-size="x-large"] [data-slot="dialog-content"]{width:100%!important;height:100%!important;border-radius:0!important;padding:0!important;overflow:hidden!important}',
        '[data-component="dialog"][data-size="x-large"] [data-slot="dialog-header"]{display:none!important}',
        '[data-component="dialog"][data-size="x-large"] [data-slot="dialog-close-button"]{display:none!important}',
        '.settings-dialog[data-orientation="vertical"] [role="tablist"]{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;z-index:2!important;display:flex!important;flex-direction:column!important;padding:8px 0!important;overflow-y:auto!important;background:var(--v2-background-bg-base,#111)!important;scrollbar-width:none!important}',
        '.settings-dialog[data-orientation="vertical"] [role="tablist"]::-webkit-scrollbar{display:none}',
        '.settings-dialog[data-orientation="vertical"] [role="tab"]{width:100%!important;padding:14px 20px!important;font-size:16px!important;border-radius:0!important;border-bottom:.5px solid rgba(255,255,255,.06)!important;display:flex!important;align-items:center!important;gap:12px!important;justify-content:flex-start!important}',
        '.settings-dialog[data-orientation="vertical"] [role="tab"]:active{background:rgba(255,255,255,.08)!important}',
        '.settings-dialog[data-orientation="vertical"] [role="tab"] svg{width:20px!important;height:20px!important;color:var(--v2-icon-icon-muted,#888)!important;flex-shrink:0!important}',
        '.settings-dialog[data-orientation="vertical"] [role="tabpanel"]{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;z-index:3!important;overflow-y:auto!important;background:var(--v2-background-bg-base,#111)!important;scrollbar-width:none!important;padding:0!important;display:none!important}',
        '.settings-dialog[data-orientation="vertical"] [role="tabpanel"][data-selected]{display:flex!important;flex-direction:column!important}',
        // === COMMON ===
        '.settings-v2-nav-footer{padding:16px 20px!important;border-top:.5px solid var(--v2-border-border-base,#333)!important;font-size:12px!important;color:var(--v2-text-text-faint,#666)!important}',
        '.settings-v2-nav-footer span:first-child{display:block!important;margin-bottom:4px!important}',
        '.oc-back-btn{position:sticky!important;top:0!important;z-index:10!important;display:flex!important;align-items:center!important;gap:6px!important;padding:14px 16px!important;border:none!important;background:var(--v2-background-bg-base,#111)!important;border-bottom:.5px solid var(--v2-border-border-base,#333)!important;cursor:pointer!important;color:var(--v2-text-text-accent,#5b9bf5)!important;font-size:15px!important;font-weight:500!important;width:100%!important;text-align:left!important;flex-shrink:0!important;flex-grow:0!important}',
        '.oc-menu-header{display:flex!important;align-items:center!important;justify-content:space-between!important;padding:12px 16px!important;border-bottom:.5px solid var(--v2-border-border-base,#333);flex-shrink:0!important}',
        '.oc-menu-title{font-size:17px!important;font-weight:600!important;color:var(--v2-text-text-base,#fff)!important}',
        '.oc-menu-close{display:flex!important;align-items:center!important;justify-content:center!important;width:36px!important;height:36px!important;border-radius:8px!important;border:none!important;background:transparent!important;cursor:pointer!important;flex-shrink:0!important;color:var(--v2-text-text-muted,#888)!important;padding:0!important}',
        '.oc-menu-close:active{background:rgba(255,255,255,.1)!important}',
        '}'
    ].join('');
    document.head.appendChild(css);

    // === OFFLINE: fix broken images from external URLs ===
    var _ocImgObs = new MutationObserver(function(mutations) {
        mutations.forEach(function(m) {
            m.addedNodes.forEach(function(node) {
                if (node.nodeType === 1) {
                    var imgs = node.querySelectorAll ? node.querySelectorAll('img[src*="opencode.ai"], img[src*="favicon"]') : [];
                    imgs.forEach(function(img) { img.src = 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="6" fill="%23FF9D00"/><text x="16" y="22" text-anchor="middle" font-size="18" fill="%23000" font-family="sans-serif">O</text></svg>'; });
                }
            });
        });
    });
    _ocImgObs.observe(document.body, {childList:true, subtree:true});

    var _ocMenuVisible = true;

    function ocGetSettingsDialog() {
        return document.querySelector('[data-component="dialog-v2"][data-variant="settings"]') ||
               document.querySelector('[data-component="dialog"][data-size="x-large"]');
    }

    function ocGetTabList(dialog) {
        return dialog.querySelector('[data-slot="tabs-v2-list"]') ||
               dialog.querySelector('[role="tablist"]');
    }

    function ocGetSelectedContent(dialog) {
        return dialog.querySelector('[data-slot="tabs-v2-content"][data-selected]') ||
               dialog.querySelector('[role="tabpanel"][data-selected]');
    }

    function ocGetAllContents(dialog) {
        return dialog.querySelectorAll('[data-slot="tabs-v2-content"], [role="tabpanel"]');
    }

    function ocUpdateDialog(dialog) {
        if (!dialog) return;
        var list = ocGetTabList(dialog);

        if (_ocMenuVisible) {
            if (list) { list.style.setProperty('display', 'flex', 'important'); list.style.setProperty('flex-direction', 'column', 'important'); list.style.setProperty('position', 'absolute', 'important'); list.style.setProperty('inset', '0', 'important'); list.style.setProperty('width', '100%', 'important'); list.style.setProperty('height', '100%', 'important'); list.style.setProperty('z-index', '2', 'important'); list.style.setProperty('padding', '8px 0', 'important'); list.style.setProperty('overflow-y', 'auto', 'important'); list.style.setProperty('background', 'var(--v2-background-bg-base,#111)', 'important'); }
            ocGetAllContents(dialog).forEach(function(c) { c.style.setProperty('display', 'none', 'important'); });
            ocGetAllContents(dialog).forEach(function(c) { var b = c.querySelector('.oc-back-btn'); if (b) b.remove(); });
            ocInjectMenuHeader(dialog);
        } else {
            if (list) list.style.setProperty('display', 'none', 'important');
            var target = ocGetSelectedContent(dialog);
            ocGetAllContents(dialog).forEach(function(c) { if (c !== target) c.style.setProperty('display', 'none', 'important'); });
            if (target) {
                target.style.removeProperty('display');
                target.style.setProperty('display', 'flex', 'important');
                target.style.setProperty('flex-direction', 'column', 'important');
                target.style.setProperty('position', 'absolute', 'important');
                target.style.setProperty('inset', '0', 'important');
                target.style.setProperty('width', '100%', 'important');
                target.style.setProperty('height', '100%', 'important');
                target.style.setProperty('z-index', '3', 'important');
                target.style.setProperty('overflow-y', 'auto', 'important');
                target.style.setProperty('background', 'var(--v2-background-bg-base,#111)', 'important');
                if (!target.querySelector('.oc-back-btn')) {
                    var back = document.createElement('button');
                    back.type = 'button';
                    back.className = 'oc-back-btn';
                    back.innerHTML = '\u2190 Back';
                    back.onclick = function(ev) {
                        ev.preventDefault();
                        ev.stopPropagation();
                        _ocMenuVisible = true;
                        var dlg = ocGetSettingsDialog();
                        if (dlg) ocUpdateDialog(dlg);
                    };
                    target.insertBefore(back, target.firstChild);
                }
            }
        }
    }

    function ocInjectMenuHeader(dialog) {
        if (!dialog) dialog = ocGetSettingsDialog();
        if (!dialog) return;
        var list = ocGetTabList(dialog);
        if (!list || list.querySelector('.oc-menu-header')) return;

        var hdr = document.createElement('div');
        hdr.className = 'oc-menu-header';

        var title = document.createElement('span');
        title.className = 'oc-menu-title';
        title.textContent = 'Settings';

        var closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'oc-menu-close';
        closeBtn.innerHTML = '\u2715';
        closeBtn.onclick = function(ev) {
            ev.preventDefault();
            ev.stopPropagation();
            var overlay = dialog.querySelector('[data-slot="dialog-overlay"]');
            if (overlay) { overlay.dispatchEvent(new MouseEvent('pointerdown', {bubbles:true})); overlay.dispatchEvent(new MouseEvent('click', {bubbles:true})); }
            else {
                var esc = new KeyboardEvent('keydown', {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true});
                document.dispatchEvent(esc);
            }
        };

        hdr.appendChild(title);
        hdr.appendChild(closeBtn);
        list.insertBefore(hdr, list.firstChild);
    }

    window.ocSettingsBack = function() {
        if (!_ocMenuVisible) {
            _ocMenuVisible = true;
            var dlg = ocGetSettingsDialog();
            if (dlg) ocUpdateDialog(dlg);
            return true;
        }
        return false;
    };

    window.ocIsSettingsOpen = function() {
        var dialog = ocGetSettingsDialog();
        if (!dialog) return false;
        var c = dialog.querySelector('[role="dialog"]');
        if (!c) c = dialog;
        return c.offsetHeight > 0;
    };

    var _ocObs = new MutationObserver(function() {
        if (window.innerWidth > 639) return;
        var dialog = ocGetSettingsDialog();
        if (dialog) ocUpdateDialog(dialog);
    });
    _ocObs.observe(document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['data-selected']});

    document.addEventListener('pointerdown', function(e) {
        if (window.innerWidth > 639) return;
        var tab = e.target.closest('[role="tab"]');
        if (!tab) return;
        var dialog = tab.closest('[data-component="dialog-v2"][data-variant="settings"]') ||
                     tab.closest('[data-component="dialog"]');
        if (!dialog) return;
        _ocMenuVisible = false;
        setTimeout(function() { ocUpdateDialog(dialog); }, 200);
    }, true);

    var _ocInit = setInterval(function() {
        var dialog = ocGetSettingsDialog();
        if (!dialog) return;
        if (window.innerWidth > 639) { clearInterval(_ocInit); return; }
        clearInterval(_ocInit);
        _ocMenuVisible = true;
        ocUpdateDialog(dialog);
    }, 100);

    // === PERMANENT RE-INJECT via MutationObserver ===
    var _ocLastDialog = null;
    var _ocPermObs = new MutationObserver(function() {
        if (window.innerWidth > 639) return;
        var dialog = ocGetSettingsDialog();
        if (!dialog) return;
        if (dialog !== _ocLastDialog) {
            _ocLastDialog = dialog;
            _ocMenuVisible = true;
            ocUpdateDialog(dialog);
        } else if (!dialog.querySelector('.oc-menu-header')) {
            _ocMenuVisible = true;
            ocUpdateDialog(dialog);
        }
    });
    _ocPermObs.observe(document.body, {childList:true, subtree:true, attributes:true});

    // === FOLDER PICKER: intercept Add Project button ===
    window.__folderPickerResult = null;
    window.openFolderPicker = function() {
        return new Promise(function(resolve) {
            window.__folderPickerResult = function(path) { resolve(path); };
            if (window.AndroidBridge) {
                window.AndroidBridge.openFolderPicker('folder');
            }
        });
    };

    function ocBase64Url(str) {
        var bytes = new TextEncoder().encode(str);
        var binary = Array.from(bytes, function(b) { return String.fromCharCode(b); }).join('');
        return btoa(binary).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=/g, '');
    }

    function ocOpenProjectAndroid(path) {
        var key = 'opencode.global.dat:server';
        var data = {};
        try { data = JSON.parse(localStorage.getItem(key) || '{}'); } catch(e) {}
        if (!data.projects) data.projects = {};
        if (!data.projects.local) data.projects.local = [];
        var exists = data.projects.local.some(function(p) { return p.worktree === path; });
        if (!exists) {
            data.projects.local.unshift({ worktree: path, expanded: true });
        }
        if (!data.lastProject) data.lastProject = {};
        data.lastProject.local = path;
        localStorage.setItem(key, JSON.stringify(data));
        window.location.reload();
    }

    function ocInterceptAddProject(e) {
        var btn = e.target.closest('button');
        if (!btn) return;
        var isAddBtn = btn.getAttribute('data-action') === 'home-add-project' ||
                       btn.getAttribute('aria-label') === 'Add project' ||
                       btn.getAttribute('aria-label') === '\u041E\u0442\u043A\u0440\u044B\u0442\u044C \u043F\u0440\u043E\u0435\u043A\u0442';
        if (!isAddBtn) return;
        e.preventDefault();
        e.stopPropagation();
        window.openFolderPicker().then(function(path) {
            if (path) {
                ocOpenProjectAndroid(path);
            }
        });
        return false;
    }
    document.addEventListener('click', ocInterceptAddProject, true);

    // === FOLDER PICKER: inside dialog ===
    var _ocDirObs = new MutationObserver(function() {
        var dialogs = document.querySelectorAll('[role="dialog"], [data-component="dialog"]');
        dialogs.forEach(function(dialog) {
            var text = dialog.textContent || '';
            if (text.includes('Open Project') || text.includes('Select') || text.includes('directory') || text.includes('folder') || text.includes('project')) {
                var inputs = dialog.querySelectorAll('input[type="text"], input:not([type])');
                inputs.forEach(function(input) {
                    if (!input.dataset.androidPickerAdded) {
                        input.dataset.androidPickerAdded = 'true';
                        var btn = document.createElement('button');
                        btn.textContent = '\uD83D\uDCF1 Pick folder';
                        btn.style.cssText = 'margin-left:8px;padding:4px 8px;background:#3b5cf6;color:white;border:none;border-radius:4px;cursor:pointer;font-size:12px';
                        btn.onclick = function() {
                            window.openFolderPicker().then(function(path) {
                                if (path) {
                                    input.value = path;
                                    input.dispatchEvent(new Event('input', {bubbles:true}));
                                    input.dispatchEvent(new Event('change', {bubbles:true}));
                                }
                            });
                        };
                        input.parentNode.insertBefore(btn, input.nextSibling);
                    }
                });
            }
        });
    });
    _ocDirObs.observe(document.body, {childList:true, subtree:true});

    // === NOTIFICATIONS ===
    var lastThinkingState = false;
    setInterval(function() {
        var el = document.querySelector('[data-component="thinking"], .thinking, [class*="loading"], [class*="spinner"]');
        var isThinking = el !== null;
        if (lastThinkingState && !isThinking) {
            if (window.AndroidBridge) window.AndroidBridge.notifyTaskComplete('OpenCode', 'Task completed');
        }
        lastThinkingState = isThinking;
    }, 1000);

    var _ocMsgObs = new MutationObserver(function(mutations) {
        mutations.forEach(function(m) {
            m.addedNodes.forEach(function(node) {
                if (node.nodeType === 1) {
                    var role = node.getAttribute('data-role') || node.getAttribute('role');
                    if (role === 'assistant') {
                        setTimeout(function() {
                            var streaming = node.querySelector('[data-streaming="true"], .streaming');
                            if (!streaming && window.AndroidBridge) window.AndroidBridge.notifyTaskComplete('OpenCode', 'Response received');
                        }, 2000);
                    }
                }
            });
        });
    });
    _ocMsgObs.observe(document.body, {childList:true, subtree:true});
})();
                    """.trimIndent()
                    evaluateJavascript(js, null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val url = request?.url?.toString() ?: ""
                    val desc = error?.description ?: ""
                    val code = error?.errorCode ?: -1
                    android.util.Log.e("WV_ERR", "code=$code desc=$desc url=$url")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    val url = request?.url?.toString() ?: ""
                    val status = errorResponse?.statusCode ?: 0
                    val ctype = errorResponse?.mimeType ?: "?"
                    android.util.Log.e("API_LOG", "ERR http=$status ctype=$ctype url=$url")
                    if ((url.contains("127.0.0.1") || url.contains("localhost")) &&
                        request?.method?.uppercase() == "GET"
                    ) {
                        Thread {
                            try {
                                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                c.requestMethod = "GET"
                                c.connectTimeout = 8000
                                c.readTimeout = 8000
                                val code = c.responseCode
                                val stream = if (code >= 400) c.errorStream else c.inputStream
                                val body = stream?.bufferedReader()?.readText()?.take(1000) ?: ""
                                android.util.Log.e("API_LOG", "ERRBODY($code) $url -> $body")
                            } catch (e: Exception) {
                                android.util.Log.e("API_LOG", "ERRBODYFAIL $url ${e.message}")
                            }
                        }.start()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (!url.startsWith(serverUrl) && !url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
                        return true
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message() ?: return true
                    val level = consoleMessage?.messageLevel() ?: ConsoleMessage.MessageLevel.LOG
                    val source = consoleMessage?.sourceId() ?: ""
                    val line = consoleMessage?.lineNumber() ?: 0
                    val shortSource = source.removePrefix(serverUrl).take(80)
                    when (level) {
                        ConsoleMessage.MessageLevel.ERROR -> android.util.Log.e("WV_JS", "$shortSource:$line $msg")
                        ConsoleMessage.MessageLevel.WARNING -> android.util.Log.w("WV_JS", "$shortSource:$line $msg")
                        ConsoleMessage.MessageLevel.DEBUG -> android.util.Log.d("WV_JS", "$shortSource:$line $msg")
                        ConsoleMessage.MessageLevel.TIP -> android.util.Log.i("WV_JS", "$shortSource:$line $msg")
                        else -> android.util.Log.i("WV_JS", "$shortSource:$line $msg")
                    }
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
        if (uri.toString().startsWith("content://com.android.externalstorage.documents/tree/")) {
            val docId = uri.lastPathSegment ?: return null
            return "/storage/${docId.replace(":", "/")}"
        }
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

    private fun jsonResponse(body: String): WebResourceResponse {
        return WebResourceResponse(
            "application/json",
            "UTF-8",
            java.io.ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun proxyApiGet(url: String, headers: Map<String, String>?): WebResourceResponse? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers?.forEach { (k, v) ->
                if (!k.equals("Accept-Encoding", ignoreCase = true)) conn.setRequestProperty(k, v)
            }
            conn.connectTimeout = 15000
            conn.readTimeout = 0
            val code = conn.responseCode
            val ctype = conn.contentType ?: ""
            android.util.Log.i("API_LOG", "RESP $code ctype=$ctype url=$url")
            if (ctype.contains("text/html", ignoreCase = true)) {
                val bytes = try { conn.inputStream.readBytes() } catch (_: Exception) { ByteArray(0) }
                android.util.Log.e("API_LOG", "RESHTML $url -> ${String(bytes, Charsets.UTF_8).take(800)}")
                return WebResourceResponse(ctype, conn.contentEncoding ?: "UTF-8", java.io.ByteArrayInputStream(bytes))
            }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            WebResourceResponse(ctype, conn.contentEncoding ?: "UTF-8", stream)
        } catch (e: Exception) {
            android.util.Log.e("API_LOG", "PROXYFAIL $url ${e.message}")
            null
        }
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webview.visibility == View.VISIBLE) {
            binding.webview.evaluateJavascript("window.ocIsSettingsOpen()") { isSettings ->
                if (isSettings == "true") {
                    binding.webview.evaluateJavascript("window.ocSettingsBack()") { wentBack ->
                        if (wentBack != "true") {
                            runOnUiThread { super.onBackPressed() }
                        }
                    }
                } else if (binding.webview.canGoBack()) {
                    binding.webview.goBack()
                } else {
                    runOnUiThread { super.onBackPressed() }
                }
            }
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
