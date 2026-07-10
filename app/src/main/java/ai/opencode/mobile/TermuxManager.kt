package ai.opencode.mobile

import android.content.Context
import android.system.Os
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.*

class TermuxManager(private val context: Context) {

    private var terminalSession: TerminalSession? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var onStatusChange: ((String) -> Unit)? = null
    var onLogLine: ((String) -> Unit)? = null
    var onStepUpdate: ((step: String, status: String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    private var serverReady = false
    private var serverPort = 0
    private var lastTextLen = 0

    private val TAG = "TermuxManager"
    private val prefixDir get() = context.filesDir
    private val nativeLibDir get() = context.applicationInfo.nativeLibraryDir

    private val logFile by lazy { File(context.filesDir, "termux.log") }
    private fun log(msg: String) {
        android.util.Log.d(TAG, msg)
        try { logFile.appendText("${System.currentTimeMillis()} $msg\n") } catch (_: Exception) {}
        onLogLine?.invoke(msg)
    }
    private fun logErr(msg: String) {
        android.util.Log.e(TAG, msg)
        try { logFile.appendText("${System.currentTimeMillis()} ERR: $msg\n") } catch (_: Exception) {}
        onLogLine?.invoke("ERR: $msg")
    }

    fun isRunning(): Boolean = terminalSession != null
    fun isServerReady(): Boolean = serverReady
    fun getServerPort(): Int = serverPort
    fun getServerUrl(): String = "http://127.0.0.1:$serverPort"

    fun start(projectPath: String) {
        if (isRunning()) return

        scope.launch {
            try {
                log("=== start projectPath=$projectPath ===")
                log("prefixDir=$prefixDir exists=${prefixDir.exists()}")
                log("nativeLibDir=$nativeLibDir exists=${File(nativeLibDir).exists()}")

                onStepUpdate?.invoke("setup", "running")
                onStatusChange?.invoke("Setting up Alpine Linux...")
                setupAlpine()
                onStepUpdate?.invoke("setup", "done")

                onStepUpdate?.invoke("shell", "running")
                onStatusChange?.invoke("Starting terminal...")
                withContext(Dispatchers.Main) {
                    startTerminal(projectPath)
                }
            } catch (e: Exception) {
                logErr("ERROR: ${e.message}\n${android.util.Log.getStackTraceString(e)}")
                onStatusChange?.invoke("Error: ${e.message}")
                onError?.invoke("${e.message}")
            }
        }
    }

    private fun setupAlpine() {
        val alpineDir = File(prefixDir, "alpine")
        alpineDir.mkdirs()

        File(prefixDir, "tmp").mkdirs()

        if (!File(alpineDir, "bin/busybox").exists()) {
            log("Extracting alpine.rootfs from assets...")
            try {
                context.assets.open("alpine.rootfs").use { input ->
                    val tmpFile = File(context.cacheDir, "alpine.rootfs")
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                    val process = ProcessBuilder("tar", "xzf", tmpFile.absolutePath, "-C", alpineDir.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()
                    log("tar exit=$exitCode")
                    tmpFile.delete()
                }
                log("Rootfs extracted to $alpineDir")
            } catch (e: Exception) {
                logErr("Failed to extract rootfs: ${e.message}")
            }
        } else {
            log("Rootfs already extracted")
        }

        try {
            ProcessBuilder("chmod", "-R", "755", alpineDir.absolutePath)
                .redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) {}

        File(alpineDir, "tmp").mkdirs()
        File(alpineDir, "proc").mkdirs()
        File(alpineDir, "dev").mkdirs()
        File(alpineDir, "sys").mkdirs()

        val etcDir = File(alpineDir, "etc")
        etcDir.mkdirs()
        val resolvConf = File(etcDir, "resolv.conf")
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        log("Created /etc/resolv.conf with Google DNS")

        // Copy proot binaries + opencode-server to PREFIX/bin so SELinux allows execution
        // Android only extracts shared libs to nativeLibDir, not PIE executables
        val binDir = File(prefixDir, "bin")
        binDir.mkdirs()

        val requiredBinaries = listOf("libproot-xed.so", "libproot.so", "libtalloc.so", "opencode-server")
        val allExist = requiredBinaries.all { File(binDir, it).exists() }

        if (!allExist) {
            log("Extracting binaries from APK...")
            try {
                val apk = java.util.zip.ZipFile(context.applicationInfo.sourceDir)
                val entries = apk.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("lib/arm64-v8a/")) {
                        val soName = entry.name.substringAfterLast("/")
                        if (soName == "libproot-xed.so" || soName == "libproot.so" ||
                            soName == "libtalloc.so" || soName == "opencode-server") {
                            val outFile = File(binDir, soName)
                            apk.getInputStream(entry).use { input ->
                                FileOutputStream(outFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            outFile.setExecutable(true)
                            log("Extracted $soName to $outFile (${outFile.length()} bytes)")
                        }
                    }
                }
                apk.close()
            } catch (e: Exception) {
                logErr("Failed to extract binaries: ${e.message}")
            }

            // Extract opencode-server from assets if not found in jniLibs
            if (!File(binDir, "opencode-server").exists()) {
                log("Extracting opencode-server from assets...")
                try {
                    context.assets.open("opencode-server").use { input ->
                        val outFile = File(binDir, "opencode-server")
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                        outFile.setExecutable(true)
                        log("Extracted opencode-server from assets (${outFile.length()} bytes)")
                    }
                } catch (e: Exception) {
                    logErr("Failed to extract opencode-server from assets: ${e.message}")
                }
            }
        } else {
            log("Binaries already extracted")
        }

        val libtalloc2 = File(binDir, "libtalloc.so.2")
        try { libtalloc2.delete() } catch (_: Exception) {}
        try {
            Os.symlink("libtalloc.so", libtalloc2.absolutePath)
            log("Created libtalloc.so.2 symlink -> libtalloc.so")
        } catch (e: Exception) {
            logErr("Failed to create symlink: ${e.message}")
        }

        log("Alpine setup complete")
    }

    private fun startTerminal(projectPath: String) {
        val P = prefixDir.absolutePath
        val N = nativeLibDir

        val env = arrayOf(
            "PREFIX=$P",
            "NATIVE_DIR=$nativeLibDir",
            "HOME=$P/alpine/root",
            "TERM=xterm-256color"
        )

        val session = TerminalSession(
            "/system/bin/sh",
            projectPath,
            arrayOf(),
            env,
            24,
            object : TerminalSessionClient {
                override fun onTextChanged(@NonNull session: TerminalSession) {
                    val text = try {
                        session.emulator?.screen?.transcriptText ?: ""
                    } catch (e: Exception) { "" }

                    if (text.length > lastTextLen) {
                        val newText = text.substring(lastTextLen)
                        lastTextLen = text.length
                        newText.lines().forEach { line ->
                            if (line.isNotBlank()) {
                                log("TERM: $line")
                                // Detect server ready from opencode-server output
                                if (line.contains("opencode-server listening on")) {
                                    serverReady = true
                                    // Extract port from line like "opencode-server listening on http://0.0.0.0:4096"
                                    val portMatch = Regex(":(\\d+)").find(line)
                                    serverPort = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: 4096
                                    log("=== SERVER READY on port $serverPort ===")
                                    onReady?.invoke()
                                }
                                // Also detect old format for compatibility
                                if (line.contains("OpenCode server started")) {
                                    serverReady = true
                                    log("=== SERVER READY ===")
                                    onReady?.invoke()
                                }
                            }
                        }
                    }
                }
                override fun onTitleChanged(@NonNull session: TerminalSession) {}
                override fun onSessionFinished(@NonNull session: TerminalSession) {
                    logErr("=== SESSION FINISHED exit=${session.exitStatus} ===")
                    onError?.invoke("Shell exited with code ${session.exitStatus}")
                }
                override fun onCopyTextToClipboard(@NonNull session: TerminalSession, text: String) {}
                override fun onPasteTextFromClipboard(@Nullable session: TerminalSession?) {}
                override fun onBell(@NonNull session: TerminalSession) {}
                override fun onColorsChanged(@NonNull session: TerminalSession) {}
                override fun onTerminalCursorStateChange(state: Boolean) {}
                override fun setTerminalShellPid(@NonNull session: TerminalSession, pid: Int) {
                    log("Shell PID=$pid")
                }
                override fun getTerminalCursorStyle(): Int? = null
                override fun logError(tag: String, message: String) { logErr("[$tag] $message") }
                override fun logWarn(tag: String, message: String) { log("[$tag] WARN: $message") }
                override fun logInfo(tag: String, message: String) { log("[$tag] INFO: $message") }
                override fun logDebug(tag: String, message: String) { log("[$tag] DEBUG: $message") }
                override fun logVerbose(tag: String, message: String) {}
                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { logErr("[$tag] $message: ${e.message}") }
                override fun logStackTrace(tag: String, e: Exception) { logErr("[$tag] ${e.message}") }
            }
        )

        session.mSessionName = "opencode"
        terminalSession = session
        session.updateSize(80, 24, 0, 0)
        log("Terminal session created")

        val cmd = "export LD_LIBRARY_PATH=$P/bin:$N && " +
            "export PROOT_TMP_DIR=$P/tmp && " +
            "mkdir -p $P/tmp 2>/dev/null && " +
            "export PROOT_LOADER=$P/bin/libproot.so && " +
            "export PROOT=$P/bin/libproot-xed.so && " +
            "echo [SANDBOX] PROOT=$P/bin/libproot-xed.so && " +
            "echo [SANDBOX] PROOT_LOADER=$P/bin/libproot.so && " +
            "echo [SANDBOX] Starting proot... && " +
            "exec $P/bin/libproot-xed.so --kill-on-exit " +
            "-b /apex -b /odm -b /product -b /system -b /system_ext -b /vendor " +
            "-b /sdcard -b /storage -b /dev -b /data " +
            "-b /dev/urandom:/dev/random -b /proc -b /sys " +
            "-b $P -b $N -b $P/alpine/tmp:/dev/shm " +
            "-r $P/alpine -0 --link2symlink --sysvipc -L " +
            "/bin/sh -c 'export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:$P/bin; " +
            "export HOME=/root; export TERM=xterm-256color; " +
            "echo [ALPINE] Installing packages...; " +
            "apk update >/dev/null 2>&1 && apk add --no-cache bash git nodejs npm curl >/dev/null 2>&1; " +
            "echo [ALPINE] Starting opencode-server...; " +
            "chmod +x $P/bin/opencode-server 2>/dev/null; " +
            "OPENCODE_HOST=0.0.0.0 OPENCODE_PORT=0 $P/bin/opencode-server & " +
            "exec /bin/bash --rcfile /etc/profile -i'"

        log("CMD: $cmd")
        session.write(cmd + "\n")
        onStepUpdate?.invoke("shell", "done")
    }

    fun sendCommand(command: String) {
        log("sendCommand: $command")
        terminalSession?.write(command + "\n")
    }

    fun getTerminalSession(): TerminalSession? = terminalSession

    fun getLogText(): String = try {
        if (logFile.exists()) logFile.readText() else ""
    } catch (_: Exception) { "" }

    fun stop() {
        terminalSession?.finishIfRunning()
        terminalSession = null
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    fun destroy() { stop() }
}
