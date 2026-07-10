package ai.opencode.mobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ai.opencode.mobile.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var termuxManager: TermuxManager
    private var backendReady = false
    private val SERVER_URL = "http://127.0.0.1:4096"

    private val logLines = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

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
                binding.statusText.text = "Ready!"
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
        startBackend()
    }

    private fun updateStepUI(step: String, status: String) {
        // Map new step names to existing UI elements
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

            webViewClient = object : WebViewClient() {
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
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    android.util.Log.d("WebView", consoleMessage?.message() ?: "")
                    return true
                }
            }
        }
    }

    private fun loadWebView() {
        binding.webview.loadUrl(SERVER_URL)
        binding.splashScreen.visibility = View.GONE
        binding.webview.visibility = View.VISIBLE
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
