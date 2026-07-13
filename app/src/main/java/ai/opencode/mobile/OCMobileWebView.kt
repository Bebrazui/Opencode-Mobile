package ai.opencode.mobile

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import android.webkit.WebView

class OCMobileWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var onImageCommit: ((android.net.Uri) -> Unit)? = null

    private val supportedMimes = arrayOf(
        "image/png", "image/gif", "image/jpeg", "image/webp", "image/heic", "image/heif"
    )

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val inputConnection = super.onCreateInputConnection(outAttrs) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            outAttrs.contentMimeTypes = supportedMimes
        }
        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
            val uri = inputContentInfo.contentUri ?: return@OnCommitContentListener false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
            ) {
                try {
                    inputContentInfo.requestPermission()
                } catch (_: Exception) {
                }
            }
            onImageCommit?.invoke(uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                try {
                    inputContentInfo.releasePermission()
                } catch (_: Exception) {
                }
            }
            true
        }
        return InputConnectionCompat.createWrapper(inputConnection, outAttrs, callback)
    }
}
