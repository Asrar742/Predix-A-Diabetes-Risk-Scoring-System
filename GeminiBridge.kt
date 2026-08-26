package com.predix.app

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GeminiBridge
 *
 * JavaScript interface injected into the WebView as "GeminiAI".
 * The HTML page calls: GeminiAI.analyzeReport(uriString)
 * This class calls Gemini, then sends the result back to JS via:
 *   window.onGeminiResult(jsonString)
 *   window.onGeminiError(errorString)
 */
class GeminiBridge(
    private val context: Context,
    private val webView: WebView,
    private val scope: CoroutineScope
) {

    private val analyzer = GeminiAnalyzer(context)

    companion object {
        const val JS_INTERFACE_NAME = "GeminiAI"
        private const val TAG = "GeminiBridge"
    }

    /**
     * Called from JavaScript:
     *   GeminiAI.analyzeReport("content://media/external/images/...")
     */
    @JavascriptInterface
    fun analyzeReport(uriString: String) {
        Log.d(TAG, "analyzeReport called with URI: $uriString")

        scope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val result = analyzer.analyzeReport(uri)

                withContext(Dispatchers.Main) {
                    when (result) {
                        is GeminiAnalyzer.AnalysisResult.Success -> {
                            // Escape JSON for safe injection into JavaScript string
                            val escaped = result.json
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\n", "\\n")
                                .replace("\r", "")
                            webView.evaluateJavascript(
                                "window.onGeminiResult('$escaped');",
                                null
                            )
                        }
                        is GeminiAnalyzer.AnalysisResult.Error -> {
                            val msg = result.message.replace("'", "\\'")
                            webView.evaluateJavascript(
                                "window.onGeminiError('$msg');",
                                null
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bridge error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    val msg = (e.message ?: "Unknown error").replace("'", "\\'")
                    webView.evaluateJavascript(
                        "window.onGeminiError('$msg');",
                        null
                    )
                }
            }
        }
    }

    /**
     * Called from JavaScript to check if Gemini is available:
     *   GeminiAI.isAvailable()
     */
    @JavascriptInterface
    fun isAvailable(): Boolean = BuildConfig.GEMINI_API_KEY != "YOUR_GEMINI_API_KEY_HERE"

    /**
     * Called from JavaScript to get the model name:
     *   GeminiAI.getModelName()
     */
    @JavascriptInterface
    fun getModelName(): String = "gemini-1.5-flash"
}
