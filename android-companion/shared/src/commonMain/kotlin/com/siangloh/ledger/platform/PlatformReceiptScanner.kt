package com.siangloh.ledger.platform

expect object PlatformReceiptScanner {
    fun recognizeTextFromImageBytes(
        imageBytes: ByteArray,
        onResult: (Result<List<String>>) -> Unit
    )
}
