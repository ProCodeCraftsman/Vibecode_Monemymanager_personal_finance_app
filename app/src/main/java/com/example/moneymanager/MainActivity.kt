package com.example.moneymanager

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.*
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // ── File picker result ─────────────────────────────────────
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris: Array<Uri>? = when {
                data?.clipData != null -> Array(data.clipData!!.itemCount) { i ->
                    data.clipData!!.getItemAt(i).uri
                }
                data?.data != null     -> arrayOf(data.data!!)
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
            fileChooserCallback?.onReceiveValue(uris)
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
        cameraImageUri = null
    }

    // ══════════════════════════════════════════════════════════
    //  AndroidBridge — exposed to JavaScript as window.AndroidBridge
    //  Every @JavascriptInterface method is callable from JS.
    // ══════════════════════════════════════════════════════════
    inner class AndroidBridge {

        /**
         * Called by JS: window.AndroidBridge.saveFile(filename, content)
         * Writes a plain-text file (CSV or JSON) to the public Downloads folder.
         */
        @JavascriptInterface
        fun saveFile(filename: String, content: String) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                downloadsDir.mkdirs()

                // Use a safe filename (strip any path separators from JS)
                val safeName = File(filename).name.ifBlank { "moneymanager_export.csv" }
                val outFile  = File(downloadsDir, safeName)
                outFile.writeText(content, Charsets.UTF_8)

                // Toast must run on the main thread
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "✓ Saved to Downloads: $safeName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Save failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        /**
         * Called by JS: window.AndroidBridge.authenticate()
         * Shows the native biometric / PIN prompt.
         */
        @JavascriptInterface
        fun authenticate() {
            runOnUiThread { showBiometricPrompt() }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  onCreate
    // ══════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Safe-area container (notch / nav bar padding) ─────
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

        // ── WebView settings ───────────────────────────────────
        with(webView.settings) {
            javaScriptEnabled    = true
            domStorageEnabled    = true   // localStorage — all app data lives here
            allowFileAccess      = true
            allowContentAccess   = true
            databaseEnabled      = true
            loadWithOverviewMode = true
            useWideViewPort      = true
        }

        // ── Inject bridge: now JS can call window.AndroidBridge.saveFile()
        //                   and window.AndroidBridge.authenticate()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        // ── WebViewClient: keep navigation inside the app ─────
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

        // ── WebChromeClient: file chooser + JS dialogs ─────────
        webView.webChromeClient = object : WebChromeClient() {

            // Handles every <input type="file"> tap in the HTML
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback

                // ACTION_OPEN_DOCUMENT gives full file system access including Downloads.
                // ACTION_GET_CONTENT is more restricted on newer Android versions.
                // We pass EXTRA_MIME_TYPES to show all relevant file types.
                val acceptTypes = params.acceptTypes
                    ?.filter { it.isNotBlank() }
                    ?.toTypedArray()
                    ?: arrayOf("*/*")

                // Check if this is a CSV/text import (not an image picker)
                val isDocumentPick = acceptTypes.any { mime ->
                    mime.contains("csv") || mime.contains("json") ||
                            mime.contains("text") || mime.contains("*/*")
                }

                val chooserIntent = if (isDocumentPick) {
                    // Use OPEN_DOCUMENT for full Downloads/files access
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"  // Must set type before EXTRA_MIME_TYPES
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "text/plain",
                            "application/csv",
                            "application/json",
                            "application/octet-stream",
                            "*/*"
                        ))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                } else {
                    // Image / receipt picker — use GET_CONTENT which has better
                    // gallery integration
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (acceptTypes.size == 1) acceptTypes[0] else "*/*"
                        if (acceptTypes.size > 1) {
                            putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
                        }
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                // Only offer camera for image/receipt picks, not for CSV/JSON imports
                val extras = mutableListOf<Intent>()
                if (!isDocumentPick) {
                    try {
                        val photoFile = createTempImageFile()
                        cameraImageUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            photoFile
                        )
                        extras.add(
                            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                            }
                        )
                    } catch (_: Exception) { /* camera unavailable */ }
                }

                val final = Intent.createChooser(chooserIntent, "Select File").apply {
                    if (extras.isNotEmpty())
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
                }
                filePickerLauncher.launch(final)
                return true
            }

            // JS alert() — error messages
            override fun onJsAlert(
                view: WebView, url: String, message: String, result: JsResult
            ): Boolean {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                result.confirm()
                return true
            }

            // JS confirm() — delete confirmations
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

        webView.loadUrl("file:///android_asset/MoneyManager.html")

        // ── Back button ────────────────────────────────────────
        // Smart back button: lets JS handle modals/sidebar/page history first
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Ask the web page to handle back (modals, sidebar, page history)
                webView.evaluateJavascript(
                    "(function(){ return window.handleAppBack ? window.handleAppBack() : false; })()"
                ) { result ->
                    if (result == "true") {
                        // JS handled it — do nothing, stay in app
                    } else {
                        // JS says nothing to go back to — minimize app (don't finish)
                        // This moves app to background instead of destroying it
                        isEnabled = false
                        moveTaskToBack(true)
                        isEnabled = true
                    }
                }
            }
        })
    }

    // ══════════════════════════════════════════════════════════
    //  Native BiometricPrompt
    // ══════════════════════════════════════════════════════════
    private fun showBiometricPrompt() {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            webView.evaluateJavascript("window.onBiometricResult(false);", null)
            Toast.makeText(
                this,
                "No biometrics set up. Go to Settings → Security on your phone.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                webView.evaluateJavascript("window.onBiometricResult(true);", null)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                webView.evaluateJavascript("window.onBiometricResult(false);", null)
            }
            override fun onAuthenticationFailed() { /* Android retries automatically */ }
        }

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            callback
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("MoneyManager")
                .setSubtitle("Verify your identity")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }

    // ── Temp file for camera receipt photos ───────────────────
    private fun createTempImageFile(): File {
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("RECEIPT_${ts}_", ".jpg", dir)
    }
}