package com.example.moneymanager

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.*
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    // Holds the WebView's file chooser callback while the picker is open
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Holds the URI for a camera photo while the camera is open
    private var cameraImageUri: Uri? = null

    // ── Launcher: handles result from file picker ──────────────────
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris: Array<Uri>? = when {
                data?.clipData != null -> {
                    // Multiple files selected
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)  // Single file
                cameraImageUri != null -> arrayOf(cameraImageUri!!)  // Camera photo
                else -> null
            }
            fileChooserCallback?.onReceiveValue(uris)
        } else {
            // User pressed back / cancelled — MUST call this or picker breaks forever
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
        cameraImageUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Safe-area container (respects notch + nav bar) ────────
        val container = FrameLayout(this)
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(webView)
        setContentView(container)

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin    = bars.top
                bottomMargin = bars.bottom
                leftMargin   = bars.left
                rightMargin  = bars.right
            }
            insets
        }

        // ── WebView Settings ───────────────────────────────────────
        with(webView.settings) {
            javaScriptEnabled    = true   // required for the app to run
            domStorageEnabled    = true   // required for localStorage (all saved data)
            allowFileAccess      = true   // required to read the HTML asset file
            allowContentAccess   = true   // required to read files chosen via picker
            databaseEnabled      = true
            loadWithOverviewMode = true
            useWideViewPort      = true
        }

        // ── WebViewClient: keep all navigation inside the app ──────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme ?: ""
                if (scheme == "file" || scheme == "about" || scheme == "blob") return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }

        // ── WebChromeClient: file chooser + JS dialogs ─────────────
        webView.webChromeClient = object : WebChromeClient() {

            // This is called every time the HTML does: <input type="file">
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                // Always cancel any old pending callback first
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback

                // The intent built from params carries the correct MIME type filter
                // e.g. "image/*" for receipts, ".csv" for imports
                val chooserIntent = params.createIntent().apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                // Also add camera as an option (for receipt photos)
                val extraIntents = mutableListOf<Intent>()
                try {
                    val photoFile = createTempImageFile()
                    cameraImageUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    }
                    extraIntents.add(cameraIntent)
                } catch (_: Exception) {
                    // Camera unavailable on this device — file picker still works fine
                }

                val finalIntent = Intent.createChooser(chooserIntent, "Select File").apply {
                    if (extraIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
                    }
                }

                filePickerLauncher.launch(finalIntent)
                return true
            }

            // JS alert() — used for error messages in the app
            override fun onJsAlert(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                result.confirm()
                return true
            }

            // JS confirm() — used for "Delete this transaction?" dialogs
            override fun onJsConfirm(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK")     { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel()  }
                    .setCancelable(false)
                    .show()
                return true
            }
        }

        // ── Download listener: intercepts CSV/JSON exports ─────────
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        // ── Load the app ───────────────────────────────────────────
        webView.loadUrl("file:///android_asset/MoneyManager.html")

        // ── Back button ───────────────────────────────────────────
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    // ── Creates a timestamped temp file for camera photos ─────────
    private fun createTempImageFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("IMG_${ts}_", ".jpg", dir)
    }

    // ── Handles file downloads triggered by the app ────────────────
    // The app uses the standard HTML <a download> pattern,
    // which WebView fires through this listener as a blob: or data: URL.
    // We intercept it and save to the Downloads folder via DownloadManager.
    private fun handleDownload(
        url: String, userAgent: String, contentDisposition: String, mimetype: String
    ) {
        try {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                // Inject JS to convert blob URL → base64 → trigger Android share
                webView.evaluateJavascript("""
                    (async function() {
                        try {
                            var response = await fetch('$url');
                            var blob = await response.blob();
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                Android.receiveBase64File(reader.result,
                                    '${mimetype.replace("'","\\'")}',
                                    '${guessFilename(contentDisposition, mimetype)}');
                            };
                            reader.readAsDataURL(blob);
                        } catch(e) {
                            console.log('Download bridge error: ' + e);
                        }
                    })();
                """.trimIndent(), null)
            } else {
                // Regular URL — use DownloadManager directly
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Downloading...")
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, filename
                    )
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Saving to Downloads...", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessFilename(contentDisposition: String, mimetype: String): String {
        val fromDisp = Regex("""filename="?([^";\s]+)"?""")
            .find(contentDisposition)?.groupValues?.get(1)
        if (!fromDisp.isNullOrBlank()) return fromDisp
        return when {
            mimetype.contains("csv")  -> "moneymanager_export.csv"
            mimetype.contains("json") -> "moneymanager_backup.json"
            mimetype.contains("pdf")  -> "receipt.pdf"
            else -> "moneymanager_file"
        }
    }
}