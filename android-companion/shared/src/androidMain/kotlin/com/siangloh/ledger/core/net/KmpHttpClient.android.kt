package com.siangloh.ledger.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    engine {
        config {
            connectTimeout(12, TimeUnit.SECONDS)
            readTimeout(18, TimeUnit.SECONDS)
        }
    }
}
