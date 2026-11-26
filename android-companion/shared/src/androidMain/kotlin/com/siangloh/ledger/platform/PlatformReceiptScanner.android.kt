package com.siangloh.ledger.platform

actual object PlatformReceiptScanner {
    var androidOcrHandler: ((ByteArray, (Result<List<String>>) -> Unit) -> Unit)? = null

    actual fun recognizeTextFromImageBytes(
        imageBytes: ByteArray,
        onResult: (Result<List<String>>) -> Unit
    ) {
        val handler = androidOcrHandler
        if (handler != null) {
            handler(imageBytes, onResult)
        } else {
            onResult(Result.failure(IllegalStateException("Android OCR handler is not registered")))
        }
    }
}
