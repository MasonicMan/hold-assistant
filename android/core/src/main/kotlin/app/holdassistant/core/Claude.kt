package app.holdassistant.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonSchemaLocalValidation
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredMessageCreateParams
import com.anthropic.models.messages.StructuredOutputConfig
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.time.Duration

/** Claude model for both tiers: small and fast, per the spec's "Haiku-class". */
const val CLAUDE_MODEL = "claude-haiku-5-5"

fun claudeClient(apiKey: String, timeout: Duration = Duration.ofSeconds(8)): AnthropicClient =
    AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(1).build()

@Suppress("EnumEntryName")
enum class WireState { hold, ivr_menu, human, unsure }

class LineVerdictJson {
    @JvmField var state: WireState = WireState.unsure
    @JvmField @JsonPropertyDescription("0 to 1") var confidence: Double = 0.0
    @JvmField @JsonPropertyDescription("A few words on why") var reason: String = ""
}

/** Tier 3 of the call classifier. Returns null (treated as unsure) on any failure. */
class ClaudeLineTier(
    private val client: AnthropicClient,
    private val model: String = CLAUDE_MODEL,
    private val log: (String) -> Unit = {},
) : LlmTier {
    fun params(window: List<Pair<Double, String>>, current: String): StructuredMessageCreateParams<LineVerdictJson> {
        val lines = window.joinToString("\n") { (t, text) ->
            "[%6.1fs] %s".format(t, text.ifBlank { "(no speech)" })
        }
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(1024L)
            .system(LINE_SYSTEM)
            .outputConfig(
                StructuredOutputConfig.builder<LineVerdictJson>()
                    .format(LineVerdictJson::class.java)
                    .effort(OutputConfig.Effort.LOW)
                    .build(),
            )
            .addUserMessage(
                "Transcript, last ~30 s:\n$lines\n\nLatest segment: \"$current\"\n" +
                    "Give state, confidence from 0 to 1, and a short reason.",
            )
            .build()
    }

    override fun classify(window: List<Pair<Double, String>>, current: String): LlmVerdict? {
        val response = try {
            client.messages().create(params(window, current))
        } catch (e: RateLimitException) {
            log("Claude rate limited: ${e.message}"); return null
        } catch (e: AnthropicServiceException) {
            log("Claude API error ${e.statusCode()}: ${e.message}"); return null
        } catch (e: AnthropicIoException) {
            log("Claude network error: ${e.message}"); return null
        }
        if (response.stopReason().orElse(null) == StopReason.REFUSAL) return null
        val v = response.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() } ?: return null
        val state = LineState.fromWire(v.state.name) ?: return null
        return LlmVerdict(state, v.confidence, v.reason)
    }

    companion object {
        const val LINE_SYSTEM = """You classify a live transcript of a phone call to an automated line (bank, utility, big company), heard from the caller's side while they wait.

Label the LATEST segment as one of:
- hold: recorded hold messages, ads and promos, transfer notices, wait-time announcements, music or silence.
- ivr_menu: an automated menu or prompt that wants the caller's input (press, say, enter, "tell me what you're calling about", echo-back confirmations).
- human: a live person speaking (addresses the caller directly, asks for details, refers to their specific account or problem, hesitates or uses fillers).
- unsure: you cannot tell.

Recordings are scripted and polished; people are not. Text that repeats earlier text nearly verbatim is a recording. If torn between human and hold, answer unsure."""
    }
}

class ContactLabelJson {
    @JvmField var id: String = ""
    @JvmField @JsonPropertyDescription("True for a business, agency or other organization; false for a person")
    var institution: Boolean = false
    @JvmField @JsonPropertyDescription("Short category, e.g. bank, carrier, insurer, government, person")
    var category: String = ""
    @JvmField @JsonPropertyDescription("0 to 1") var confidence: Double = 0.0
}

class ContactLabelsJson {
    @JvmField var results: List<ContactLabelJson> = emptyList()
}

data class ContactLabel(val id: String, val institution: Boolean, val category: String, val confidence: Double)

/**
 * Second opinion for contacts the heuristics left UNCLEAR. Sends only names,
 * company and job title, plus whether a number is toll-free; never phone numbers.
 */
class ClaudeContactClassifier(
    private val client: AnthropicClient,
    private val model: String = CLAUDE_MODEL,
    private val log: (String) -> Unit = {},
) {
    fun params(contacts: List<ContactInfo>): StructuredMessageCreateParams<ContactLabelsJson> {
        val rows = contacts.joinToString("\n") { c ->
            listOfNotNull(
                "id=${c.id}",
                "name=\"${c.displayName}\"",
                c.organization?.takeIf { it.isNotBlank() }?.let { "company=\"$it\"" },
                c.jobTitle?.takeIf { it.isNotBlank() }?.let { "title=\"$it\"" },
                "toll_free=${c.phones.any { InstitutionClassifier.isTollFree(it.number.filter(Char::isDigit)) }}",
            ).joinToString(" ")
        }
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(8192L)
            .system(CONTACT_SYSTEM)
            .outputConfig(
                StructuredOutputConfig.builder<ContactLabelsJson>()
                    .format(ContactLabelsJson::class.java, JsonSchemaLocalValidation.YES)
                    .effort(OutputConfig.Effort.LOW)
                    .build(),
            )
            .addUserMessage("Contacts:\n$rows")
            .build()
    }

    /** Labels for the given contacts; contacts missing from the reply are left out. */
    fun classify(contacts: List<ContactInfo>): List<ContactLabel> = contacts.chunked(60).flatMap { batch ->
        val response = try {
            client.messages().create(params(batch))
        } catch (e: AnthropicServiceException) {
            log("Claude API error ${e.statusCode()}: ${e.message}"); return@flatMap emptyList()
        } catch (e: AnthropicIoException) {
            log("Claude network error: ${e.message}"); return@flatMap emptyList()
        }
        if (response.stopReason().orElse(null) == StopReason.REFUSAL) return@flatMap emptyList()
        val ids = batch.map { it.id }.toSet()
        response.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() }?.results.orEmpty()
            .filter { it.id in ids }
            .map { ContactLabel(it.id, it.institution, it.category, it.confidence) }
    }

    companion object {
        const val CONTACT_SYSTEM = """You sort phone contacts into people and organizations. An organization is any business, bank, utility, carrier, insurer, medical office, government agency, school or other institution someone might call and end up in a phone menu or on hold. A person is a friend, relative, coworker or individual professional saved under their own name (even if a company is listed as their employer).

Return one result per contact id given, in any order."""
    }
}
