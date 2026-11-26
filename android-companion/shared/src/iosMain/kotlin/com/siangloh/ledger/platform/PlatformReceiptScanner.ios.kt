package com.siangloh.ledger.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

actual object PlatformReceiptScanner {
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual fun recognizeTextFromImageBytes(
        imageBytes: ByteArray,
        onResult: (Result<List<String>>) -> Unit
    ) {
        if (imageBytes.isEmpty()) {
            dispatch_async(dispatch_get_main_queue()) {
                onResult(Result.success(emptyList()))
            }
            return
        }

        val imageCopy = imageBytes.copyOf()
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0UL)) {
            var completed = false
            fun deliver(result: Result<List<String>>) {
                if (completed) return
                completed = true
                dispatch_async(dispatch_get_main_queue()) {
                    onResult(result)
                }
            }

            try {
                val nsData: NSData = imageCopy.usePinned { pinned ->
                    NSData.create(
                        bytes = pinned.addressOf(0),
                        length = imageCopy.size.toULong()
                    )
                }

                val request = VNRecognizeTextRequest { req, error ->
                    if (error != null) {
                        deliver(Result.failure(IllegalStateException(error.localizedDescription)))
                        return@VNRecognizeTextRequest
                    }
                    try {
                        val observations = req?.results
                            ?.filterIsInstance<VNRecognizedTextObservation>()
                            .orEmpty()

                        // Vision boundingBox origin is bottom-left; higher Y is closer to top.
                        val sortedLines = observations
                            .sortedWith(
                                compareByDescending<VNRecognizedTextObservation> { obs ->
                                    obs.boundingBox.useContents { origin.y }
                                }.thenBy { obs ->
                                    obs.boundingBox.useContents { origin.x }
                                }
                            )
                            .mapNotNull { obs ->
                                val candidate = obs.topCandidates(1UL).firstOrNull() as? VNRecognizedText
                                candidate?.string?.trim()?.takeIf { it.isNotEmpty() }
                            }

                        deliver(Result.success(sortedLines))
                    } catch (e: Throwable) {
                        deliver(Result.failure(e))
                    }
                }

                request.recognitionLevel = VNRequestTextRecognitionLevelAccurate
                request.usesLanguageCorrection = true
                request.recognitionLanguages = listOf("zh-Hans", "en-US", "ms-MY")

                val handler = VNImageRequestHandler(
                    data = nsData,
                    options = emptyMap<Any?, Any?>()
                )
                val ok = handler.performRequests(listOf(request), error = null)
                if (!ok && !completed) {
                    deliver(Result.failure(IllegalStateException("Vision OCR request failed")))
                }
            } catch (e: Throwable) {
                deliver(Result.failure(e))
            }
        }
    }
}
