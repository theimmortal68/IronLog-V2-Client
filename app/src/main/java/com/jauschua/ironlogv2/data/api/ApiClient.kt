package com.jauschua.ironlogv2.data.api

import com.jauschua.ironlogv2.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiClient(
    baseUrl: String = BuildConfig.SERVER_BASE_URL,
    engine: HttpClientEngine? = null,
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val http: HttpClient = if (engine != null) {
        HttpClient(engine) { configure(baseUrl) }
    } else {
        HttpClient(OkHttp) { configure(baseUrl) }
    }

    private fun io.ktor.client.HttpClientConfig<*>.configure(baseUrl: String) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis  = 10_000
            requestTimeoutMillis = 10_000
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) = println(message)
            }
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
        }
        defaultRequest { url(baseUrl) }
    }
}
