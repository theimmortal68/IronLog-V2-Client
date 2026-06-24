package com.jauschua.ironlogv2.data.api

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.SerializationException
import java.io.IOException

sealed interface ApiError {
    data class Network(val cause: Throwable) : ApiError
    data class Server(val status: Int, val body: String?) : ApiError
    data class Client(val status: Int, val body: String?) : ApiError
    data class Timeout(val cause: Throwable) : ApiError
    data class Parse(val cause: Throwable) : ApiError
}

class IronLogException(val error: ApiError) : Exception(error.toString())

/** Wrap a Ktor call so [Result.failure] carries an [IronLogException] with a typed [ApiError]. */
suspend fun <T> runCatchingApi(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: HttpRequestTimeoutException) {
    Result.failure(IronLogException(ApiError.Timeout(e)))
} catch (e: ConnectTimeoutException) {
    Result.failure(IronLogException(ApiError.Timeout(e)))
} catch (e: SocketTimeoutException) {
    Result.failure(IronLogException(ApiError.Timeout(e)))
} catch (e: ClientRequestException) {
    Result.failure(IronLogException(ApiError.Client(e.response.status.value, runCatching { e.response.toString() }.getOrNull())))
} catch (e: ServerResponseException) {
    Result.failure(IronLogException(ApiError.Server(e.response.status.value, runCatching { e.response.toString() }.getOrNull())))
} catch (e: JsonConvertException) {
    Result.failure(IronLogException(ApiError.Parse(e)))
} catch (e: SerializationException) {
    Result.failure(IronLogException(ApiError.Parse(e)))
} catch (e: IOException) {
    Result.failure(IronLogException(ApiError.Network(e)))
}

/** Human-readable message for snackbars. */
fun ApiError.humanMessage(): String = when (this) {
    is ApiError.Network -> "Network error — is the server reachable?"
    is ApiError.Timeout -> "Request timed out"
    is ApiError.Server  -> "Server error ($status)"
    is ApiError.Client  -> "Bad request ($status)"
    is ApiError.Parse   -> "Couldn't parse server response"
}
