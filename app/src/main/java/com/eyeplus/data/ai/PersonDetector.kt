package com.eyeplus.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device person detection using Google ML Kit.
 *
 * Acts as a first-pass filter before calling the more expensive Gemini API.
 * ML Kit runs 100% on-device, is free, and has near-zero latency (<100ms).
 *
 * Only when ML Kit detects a person do we call Gemini API for detailed analysis.
 * This saves Gemini quota (1500/day → ~50-200 real calls/day).
 */
class PersonDetector {

    companion object {
        private const val TAG = "PersonDetector"
        private const val PERSON_LABEL = "Person"
        private const val MIN_CONFIDENCE = 0.5f
    }

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableClassification()
            .enableMultipleObjects()
            .build()
    )

    /**
     * Detect persons in the given bitmap.
     * Returns detection result with count and confidence.
     */
    suspend fun detect(bitmap: Bitmap): OnDeviceDetection = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { objects ->
                    var personCount = 0
                    var maxConfidence = 0f

                    for (obj in objects) {
                        for (label in obj.labels) {
                            if (label.text == PERSON_LABEL && label.confidence >= MIN_CONFIDENCE) {
                                personCount++
                                maxConfidence = maxOf(maxConfidence, label.confidence)
                            }
                        }
                    }

                    Log.d(TAG, "Detected $personCount person(s), confidence=$maxConfidence")
                    cont.resume(
                        OnDeviceDetection(
                            hasPerson = personCount > 0,
                            personCount = personCount,
                            confidence = maxConfidence
                        )
                    )
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Detection failed: ${e.message}", e)
                    cont.resume(OnDeviceDetection())
                }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}", e)
            cont.resume(OnDeviceDetection())
        }
    }

    /**
     * Release detector resources.
     */
    fun close() {
        try {
            detector.close()
        } catch (_: Exception) { }
    }
}
