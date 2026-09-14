package ai.rever.boss.plugin.dynamic.bottest.core

/**
 * How to shape the request body for the bot under test.
 *
 * Fields are assembled programmatically rather than interpolated into a string
 * template, so an input containing quotes or newlines cannot corrupt the JSON.
 */
data class RequestConfig(
    /** JSON field carrying the user's message. */
    val messageField: String = "message",
    /**
     * JSON field carrying prior turns as `[{"role","content"}]`. Null means the
     * target has no history parameter, and any case history is not sent.
     */
    val historyField: String? = null,
    /** Constant fields merged into every request - session ids, model names, flags. */
    val extraFields: Map<String, String> = emptyMap(),
) {
    init {
        require(messageField.isNotBlank()) { "messageField must not be blank" }
    }
}

/** How to pull the bot's reply out of the response body. */
data class ResponseConfig(
    /**
     * Dot path to the reply, e.g. `response`, `data.reply`, or
     * `choices.0.message.content` (numeric segments index arrays).
     *
     * Blank means the raw body *is* the reply, which supports bots that return
     * plain text rather than JSON.
     */
    val responsePath: String = "response",
)

/**
 * A chatbot under test, reached over HTTP. Deliberately not modelled on any
 * particular chatbot framework: anything that accepts a JSON body and returns a
 * reply can be described here.
 */
data class BotTarget(
    val url: String,
    val method: String = "POST",
    val timeoutMs: Long = 30_000,
    val headers: Map<String, String> = emptyMap(),
    val request: RequestConfig = RequestConfig(),
    val response: ResponseConfig = ResponseConfig(),
) {
    init {
        require(url.isNotBlank()) { "Target url must not be blank" }
        require(timeoutMs > 0) { "timeoutMs must be positive, was $timeoutMs" }
        require(method.isNotBlank()) { "method must not be blank" }
    }
}
