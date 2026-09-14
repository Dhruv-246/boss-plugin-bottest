package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class HttpRequestSpec(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String,
    val timeoutMs: Long,
)

data class HttpResponseSpec(
    val statusCode: Int,
    val body: String,
)

/** The bot could not be reached, or did not answer in time. */
open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TransportTimeoutException(message: String, cause: Throwable? = null) : TransportException(message, cause)

class TransportConnectionException(message: String, cause: Throwable? = null) : TransportException(message, cause)

/**
 * The single seam between the runner and the network.
 *
 * Unit tests substitute a fake, so the suite never needs a live chatbot.
 */
interface HttpTransport {
    suspend fun send(request: HttpRequestSpec): HttpResponseSpec
}

/**
 * Default transport, on the JDK's built-in HTTP client - no third-party HTTP
 * dependency.
 *
 * `HttpClient.send` blocks, so it runs under [runInterruptible]: cancelling the
 * calling coroutine interrupts the blocking call rather than leaking a thread
 * until the socket times out. Plugin code is required to be
 * cancellation-cooperative because the host wraps MCP tool calls in a timeout.
 */
class JdkHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : HttpTransport {

    override suspend fun send(request: HttpRequestSpec): HttpResponseSpec =
        runInterruptible(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(request.url))
                .timeout(Duration.ofMillis(request.timeoutMs))

            request.headers.forEach { (name, value) -> builder.header(name, value) }
            if (request.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                builder.header("Content-Type", "application/json")
            }
            builder.method(
                request.method.uppercase(),
                HttpRequest.BodyPublishers.ofString(request.body),
            )

            try {
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                HttpResponseSpec(response.statusCode(), response.body().orEmpty())
            } catch (e: java.net.http.HttpTimeoutException) {
                // Must precede IOException: the JDK's timeout exception extends it.
                throw TransportTimeoutException(
                    "Request to ${request.url} timed out after ${request.timeoutMs} ms",
                    e,
                )
            } catch (e: java.io.IOException) {
                throw TransportConnectionException(
                    "Could not reach ${request.url}: ${e.message ?: e::class.simpleName}",
                    e,
                )
            }
        }
}
