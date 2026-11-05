package io.github.thewisekd.phopencode.feature.chat.ui.switcher

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishSwitcherTest : SwitcherLanguageContract() {
    override val strings = ExpectedSwitcherStrings(
        agentLabel = { "Agent: $it" },
        modelLabel = { "Model: $it" },
        variantLabel = { "Variant: $it" },
        defaultAgent = "Default agent",
        defaultModel = "Default model",
        change = "Change agent and model",
        title = "Choose agent and model",
        close = "Close",
        agents = "Agent",
        models = "Model",
        defaultVariant = "Default",
        loading = "Loading agents and models…",
        switching = "Switching…",
        noAgents = "The server lists no agents to pick.",
        noModels = "The server lists no models to pick.",
        error = { action, reason ->
            when (action) {
                SwitcherAction.Load -> "Could not load the agents and models. $reason"
                SwitcherAction.SwitchAgent -> "Could not switch the agent. $reason"
                SwitcherAction.SwitchModel -> "Could not switch the model. $reason"
            }
        },
        reasons = mapOf(
            ChatFailure.NotConnected to "There is no connection to the server.",
            ChatFailure.Unreachable to "The server could not be reached. Try again.",
            ChatFailure.Unauthorized to "The server refused the credentials.",
            ChatFailure.NotFound to "The session, agent or model was not found.",
            ChatFailure.Conflict to "The session is busy or changed meanwhile.",
            ChatFailure.Rejected to "The server refused the request.",
            ChatFailure.ServerFailed to "The server failed.",
            ChatFailure.Unknown to "Something unexpected went wrong.",
        ),
    )
}
