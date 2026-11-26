package com.siangloh.ledger.core.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

expect fun createPlatformHttpClient(): HttpClient

data class KmpHttpResponse(
    val statusCode: Int,
    val body: String
)

object KmpHttpClient {
    val client: HttpClient by lazy { createPlatformHttpClient() }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): KmpHttpResponse {
        val requestHeaders = headers
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            for ((key, value) in requestHeaders) {
                if (!key.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                    header(key, value)
                }
            }
            setBody(jsonBody)
        }
        return KmpHttpResponse(
            statusCode = response.status.value,
            body = response.bodyAsText()
        )
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): KmpHttpResponse {
        val requestHeaders = headers
        val response = client.get(url) {
            for ((key, value) in requestHeaders) {
                header(key, value)
            }
        }
        return KmpHttpResponse(
            statusCode = response.status.value,
            body = response.bodyAsText()
        )
    }
}
