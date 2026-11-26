package com.siangloh.ledger.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
    engine {
        configureRequest {
            setTimeoutInterval(18.0)
        }
    }
}
