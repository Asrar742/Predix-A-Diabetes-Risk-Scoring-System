package com.predix.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * GeminiAnalyzer
 *
 * Sends a lab report image to Gemini Vision API and returns:
 *   - A plain-language explanation for the patient
 *   - Extracted marker values (HbA1c, glucose, cholesterol, etc.)
 *   - Risk assessment per marker
 *   - Actionable recommendations
 *
 * All as structured JSON so the HTML page can render charts from it.
 */
class GeminiAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "GeminiAnalyzer"
        private const val MODEL_NAME = "gemini-1.5-flash"
        private const val MAX_IMAGE_DIMENSION = 1024  // resize large images
        private const val IMAGE_QUALITY = 85
    }

    private val model: GenerativeModel by lazy {
        GenerativeModel(
            modelName = MODEL_NAME,
            apiKey    = BuildConfig.GEMINI_API_KEY,
            generationConfig = generationConfig {
                temperature     = 0.2f   // low temp = more precise medical output
                maxOutputTokens = 2048
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main entry point — called from MainActivity via JavaScript interface
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun analyzeReport(imageUri: Uri): AnalysisResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = loadAndResizeBitmap(imageUri)
                ?: return@withContext AnalysisResult.Error("Could not load image")

            val prompt = buildPrompt()
            val response = callGemini(bitmap, prompt)
            parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed: ${e.message}", e)
            AnalysisResult.Error("Analysis failed: ${e.message ?: "Unknown error"}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Build the Gemini prompt
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildPrompt(): String = """
You are a medical report analyser helping patients understand their lab results.
Analyse the uploaded lab report image carefully.

TASK: Extract all values and return ONLY a valid JSON object (no markdown, no code blocks, just raw JSON).

Return this exact JSON structure:
{
  "report_type": "string (e.g. Blood Test, HbA1c, Lipid Panel, Urine Test, etc.)",
  "patient_name": "string or null if not visible",
  "report_date": "string or null if not visible",
  "summary": "2-3 sentence plain English summary of the overall report for a non-medical person",
  "markers": [
    {
      "name": "marker name (e.g. HbA1c, Fasting Glucose, LDL Cholesterol)",
      "value": number or null,
      "unit": "unit string (e.g. %, mg/dL, mmol/L)",
      "reference_low": number or null,
      "reference_high": number or null,
      "status": "normal" | "borderline" | "high" | "low" | "critical",
      "what_it_means": "1-2 sentences explaining what this marker measures in simple language",
      "diabetes_relevance": "1 sentence explaining how this marker relates to diabetes risk specifically",
      "recommendation": "1 specific actionable recommendation for this marker"
    }
  ],
  "diabetes_risk_assessment": {
    "overall_risk": "low" | "moderate" | "high" | "critical",
    "risk_score": number between 0 and 100,
    "key_concerns": ["list of main concerns found"],
    "positive_findings": ["list of good results found"]
  },
  "recommendations": [
    "actionable recommendation 1",
    "actionable recommendation 2",
    "actionable recommendation 3"
  ],
  "lifestyle_advice": "2-3 sentences of specific lifestyle advice based on these results",
  "followup": "When and what kind of follow-up is recommended",
  "disclaimer": "This analysis is educational only. Always consult a qualified doctor for medical decisions."
}

IMPORTANT RULES:
- If a value is not visible or cannot be read, set it to null
- Be medically accurate but use simple language the patient can understand
- Focus especially on diabetes-related markers: HbA1c, Fasting Glucose, Post-meal Glucose, Insulin, BMI
- Also cover: LDL, HDL, Total Cholesterol, Triglycerides, Blood Pressure, Creatinine, eGFR
- If this is not a medical report at all, set report_type to "Not a medical report" and explain in summary
- Return ONLY the JSON object, nothing else
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  Call Gemini Vision API
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun callGemini(bitmap: Bitmap, prompt: String): String {
        val inputContent = content {
            image(bitmap)
            text(prompt)
        }
        val response = model.generateContent(inputContent)
        return response.text ?: throw Exception("Empty response from Gemini")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Parse Gemini response into AnalysisResult
    // ─────────────────────────────────────────────────────────────────────────
    private fun parseResponse(raw: String): AnalysisResult {
        return try {
            // Strip any accidental markdown code blocks Gemini might add
            val cleaned = raw
                .replace("```json", "")
                .replace("```", "")
                .trim()

            // Validate it's real JSON
            val json = JSONObject(cleaned)
            AnalysisResult.Success(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed, returning raw: ${e.message}")
            // Return raw text as fallback
            val fallback = JSONObject().apply {
                put("report_type", "Unknown")
                put("summary", raw.take(500))
                put("markers", org.json.JSONArray())
                put("recommendations", org.json.JSONArray())
                put("disclaimer",
                    "This analysis is educational only. Always consult a qualified doctor.")
            }
            AnalysisResult.Success(fallback.toString())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Image utilities
    // ─────────────────────────────────────────────────────────────────────────
    private fun loadAndResizeBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return null

            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (original == null) return null

            // Resize if too large (Gemini has upload limits)
            val maxDim = MAX_IMAGE_DIMENSION
            return if (original.width > maxDim || original.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(original.width, original.height)
                val newW  = (original.width  * scale).toInt()
                val newH  = (original.height * scale).toInt()
                val resized = Bitmap.createScaledBitmap(original, newW, newH, true)
                original.recycle()
                resized
            } else {
                original
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap failed: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Result sealed class
    // ─────────────────────────────────────────────────────────────────────────
    sealed class AnalysisResult {
        data class Success(val json: String) : AnalysisResult()
        data class Error(val message: String) : AnalysisResult()
    }
}
