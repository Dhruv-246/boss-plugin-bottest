package ai.rever.boss.plugin.dynamic.bottest

import ai.rever.boss.plugin.api.AiAvailability
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiReadiness
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.bottest.core.JudgeAvailability
import ai.rever.boss.plugin.dynamic.bottest.core.JudgeClient
import ai.rever.boss.plugin.dynamic.bottest.core.JudgeOutcome
import ai.rever.boss.plugin.dynamic.bottest.core.JudgeProtocol
import ai.rever.boss.plugin.dynamic.bottest.core.JudgeRequest

/**
 * Grades rubric criteria using the AI the BOSS host already has.
 *
 * This is why the plugin needs no API key of its own. `AiGatewayAPI` resolves
 * the user's configured provider and, when they are driving BOSS with a coding
 * CLI, routes the completion through that CLI's own terminal login - so a user
 * already running Claude Code in BOSS has a working judge with nothing to set
 * up, no second key and no separate spend.
 *
 * Two rules from the gateway's own contract are load-bearing here:
 *
 *  - **Resolve per call, never cache.** Plugin load order is not guaranteed, so
 *    a gateway looked up once at `register()` can cache a null forever.
 *  - **Nothing throws.** A missing gateway, an unconfigured provider and a
 *    provider out of quota are ordinary outcomes, so each becomes a
 *    [JudgeOutcome] rather than an exception. `LinkageError` is caught too: a
 *    gateway built against a different api revision is, to a caller, simply a
 *    gateway that is not there.
 */
class AiGatewayJudgeClient(
    private val contextProvider: () -> PluginContext?,
    private val timeoutMs: Long = 60_000,
    private val maxTokens: Int = 500,
) : JudgeClient {

    override suspend fun judge(request: JudgeRequest): JudgeOutcome {
        val context = contextProvider()
            ?: return JudgeOutcome.Unavailable("Plugin context is not available")

        val gateway = resolveGateway(context)
            ?: return JudgeOutcome.Unavailable(describeUnavailable(context))

        val reply = try {
            gateway.complete(
                AiRequest(
                    system = JudgeProtocol.SYSTEM_PROMPT,
                    messages = listOf(AiMessage.user(JudgeProtocol.userPrompt(request))),
                    // Grading should be as repeatable as the provider allows; a
                    // judge that scores the same response differently on a rerun
                    // makes every regression reading meaningless.
                    temperature = 0f,
                    maxTokens = maxTokens,
                    timeoutMs = timeoutMs,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return JudgeOutcome.Failed("Gateway call threw ${e::class.simpleName}: ${e.message}")
        }

        return reply.fold(
            onSuccess = { aiReply -> JudgeProtocol.parseReply(aiReply.text, aiReply.modelId) },
            onFailure = { error ->
                JudgeOutcome.Failed(error.message ?: error::class.simpleName ?: "Gateway call failed")
            },
        )
    }

    override suspend fun availability(): JudgeAvailability {
        val context = contextProvider()
            ?: return JudgeAvailability(false, "Plugin context is not available")

        val readiness = try {
            AiAvailability.check(context)
        } catch (e: Throwable) {
            return JudgeAvailability(false, "Could not determine AI readiness: ${e.message}")
        }

        // Subject-less `when`: AiReadiness is documented as an open set that must
        // always carry an else, but a subject `when` over today's constants is
        // exhaustive and warns about exactly that else. Comparing explicitly keeps
        // the defensive branch without the compiler objecting to it.
        return when {
            readiness == AiReadiness.READY -> JudgeAvailability(
                available = true,
                detail = "Using the AI provider configured in BOSS.",
                modelId = activeModelId(context),
            )

            readiness == AiReadiness.GATEWAY_MISSING -> JudgeAvailability(
                available = false,
                detail = "No AI Gateway plugin is installed. Install it from Toolbox to enable rubric scoring.",
            )

            readiness == AiReadiness.NO_PROVIDER -> JudgeAvailability(
                available = false,
                detail = "No AI provider is selected. Choose one in Settings, AI Providers - or sign in to " +
                    "a coding CLI such as Claude Code, which the gateway can route through.",
            )

            else -> JudgeAvailability(false, "AI is unavailable ($readiness).")
        }
    }

    private fun resolveGateway(context: PluginContext): AiGatewayAPI? = try {
        context.getPluginAPI(AiGatewayAPI::class.java)
    } catch (e: LinkageError) {
        null
    } catch (e: Exception) {
        null
    }

    private fun describeUnavailable(context: PluginContext): String = try {
        if (AiAvailability.check(context) == AiReadiness.NO_PROVIDER) {
            "No AI provider is selected in BOSS (Settings, AI Providers)."
        } else {
            "No AI Gateway plugin is installed, so rubric criteria cannot be scored."
        }
    } catch (e: Throwable) {
        "AI is not available in this BOSS session."
    }

    private fun activeModelId(context: PluginContext): String? = try {
        resolveGateway(context)?.activeModel()?.modelId
    } catch (e: Throwable) {
        null
    }
}
