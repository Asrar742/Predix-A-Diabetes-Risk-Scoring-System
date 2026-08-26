package com.predix.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var geminiBridge: GeminiBridge

    // Holds WebView file callback until user picks a file
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    // The URI of the last image the user selected — sent to Gemini
    private var lastSelectedImageUri: Uri? = null

    // ── File chooser result ───────────────────────────────────────────────────
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val resultCode = result.resultCode
        val data: Intent? = result.data

        if (resultCode == Activity.RESULT_OK) {
            val uris: Array<Uri>? = when {
                data?.data != null -> {
                    lastSelectedImageUri = data.data
                    arrayOf(data.data!!)
                }
                data == null && cameraUri != null -> {
                    lastSelectedImageUri = cameraUri
                    arrayOf(cameraUri!!)
                }
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    lastSelectedImageUri = clip.getItemAt(0).uri
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                else -> null
            }
            fileCallback?.onReceiveValue(uris)
        } else {
            fileCallback?.onReceiveValue(null)
        }
        fileCallback = null
        cameraUri = null
    }

    // ── Permission launchers ──────────────────────────────────────────────────
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) openChooser() else openGalleryOnly()
    }

    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> openChooser() }

    private val mediaPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> openChooser() }

    // ─────────────────────────────────────────────────────────────────────────
    //  onCreate
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor     = Color.parseColor("#0B1120")
        window.navigationBarColor = Color.parseColor("#0B1120")

        webView = WebView(this@MainActivity)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)

        // Create Gemini bridge using lifecycle scope (auto-cancelled on destroy)
        geminiBridge = GeminiBridge(
            context  = this@MainActivity,
            webView  = webView,
            scope    = lifecycleScope
        )

        setupWebView()
        webView.loadUrl("file:///android_asset/Predix.html")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WebView setup
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws: WebSettings = webView.settings

        ws.javaScriptEnabled     = true
        ws.domStorageEnabled     = true
        ws.allowFileAccess       = true
        ws.allowContentAccess    = true
        ws.mixedContentMode      = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ws.useWideViewPort       = true
        ws.loadWithOverviewMode  = true
        ws.setSupportZoom(false)
        ws.builtInZoomControls   = false
        ws.displayZoomControls   = false
        ws.mediaPlaybackRequiresUserGesture = false
        ws.cacheMode             = WebSettings.LOAD_DEFAULT

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ws.allowUniversalAccessFromFileURLs = true
            ws.allowFileAccessFromFileURLs      = true
        }

        // ── Inject Gemini JavaScript interface ────────────────────────────────
        // HTML calls: GeminiAI.analyzeReport(uriString)
        webView.addJavascriptInterface(geminiBridge, GeminiBridge.JS_INTERFACE_NAME)

        // ── WebViewClient ─────────────────────────────────────────────────────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("file://")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity,
                        "Cannot open link", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Tell the page whether Gemini is ready
                val ready = geminiBridge.isAvailable()
                view.evaluateJavascript(
                    "if(window.onGeminiReady) window.onGeminiReady($ready);",
                    null
                )
            }
        }

        // ── WebChromeClient — handles <input type="file"> ─────────────────────
        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                requestPermissionsThenOpen()
                return true
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d("PredixJS",
                    "[${msg.sourceId()}:${msg.lineNumber()}] ${msg.message()}")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Permissions
    // ─────────────────────────────────────────────────────────────────────────
    private fun requestPermissionsThenOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.READ_MEDIA_IMAGES
            if (ContextCompat.checkSelfPermission(this@MainActivity, perm)
                == PackageManager.PERMISSION_GRANTED)
                openChooser()
            else
                mediaPermLauncher.launch(perm)
        } else {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this@MainActivity, perm)
                == PackageManager.PERMISSION_GRANTED)
                openChooser()
            else
                storagePermLauncher.launch(perm)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  File chooser
    // ─────────────────────────────────────────────────────────────────────────
    private fun openChooser() {
        val extras = mutableListOf<Intent>()

        // Camera
        val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (camIntent.resolveActivity(packageManager) != null) {
            val f = createImageFile()
            if (f != null) {
                cameraUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    f
                )
                camIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                camIntent.addFlags(
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                extras.add(camIntent)
            }
        }

        // Gallery
        val galleryIntent = Intent(Intent.ACTION_PICK)
        galleryIntent.setDataAndType(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"
        )
        extras.add(galleryIntent)

        // Any file (PDF, doc, image)
        val baseIntent = Intent(Intent.ACTION_GET_CONTENT)
        baseIntent.type = "*/*"
        baseIntent.putExtra(
            Intent.EXTRA_MIME_TYPES, arrayOf(
                "image/jpeg", "image/png", "image/webp",
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain"
            )
        )
        baseIntent.addCategory(Intent.CATEGORY_OPENABLE)

        val chooser = Intent.createChooser(baseIntent, "Select Report or Photo")
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        fileChooserLauncher.launch(chooser)
    }

    private fun openGalleryOnly() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        fileChooserLauncher.launch(Intent.createChooser(intent, "Select Image"))
    }

    private fun createImageFile(): File? = try {
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(filesDir, "reports").also { it.mkdirs() }
        File(dir, "RPT_$ts.jpg")
    } catch (e: Exception) {
        Log.e("Predix", "createImageFile failed: ${e.message}")
        null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Back + Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume()  { super.onResume();  webView.onResume()  }
    override fun onPause()   { super.onPause();   webView.onPause()   }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.destroy()
        super.onDestroy()
    }
}
