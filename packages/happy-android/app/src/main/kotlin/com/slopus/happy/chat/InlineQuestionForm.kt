package com.slopus.happy.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// InlineQuestionForm.tsx and the two wrappers that answer through it:
// AskUserQuestionView (Claude, through the permission) and
// RequestUserInputView (Codex and Happy Agent, through the communication).
// Ported from happy-ios HappyCore/ChatDisplay/AgentCommunications.swift.

/** A question as the inline form shows it. */
data class InlineQuestion(
    val id: String,
    val question: String,
    val header: String,
    val options: List<AgentQuestionOption>,
    val multiSelect: Boolean = false,
    val required: Boolean? = null,
)

/** What the person has picked so far, by question id, in the order picked. */
data class InlineQuestionSelections(val picked: Map<String, List<Int>> = emptyMap()) {
    /** Multiple choice toggles; single choice selects (a second tap on the chosen option keeps it). */
    fun toggle(question: InlineQuestion, option: Int): InlineQuestionSelections {
        val current = picked[question.id].orEmpty()
        val next = when {
            !question.multiSelect -> listOf(option)
            option in current -> current - option
            else -> current + option
        }
        return copy(picked = picked + (question.id to next))
    }

    fun isSelected(question: InlineQuestion, option: Int) = picked[question.id]?.contains(option) == true

    fun allAnswered(questions: List<InlineQuestion>) = questions.all { it.required == false || picked[it.id].orEmpty().isNotEmpty() }

    /** The chosen labels, by question id. */
    fun answers(questions: List<InlineQuestion>): Map<String, List<String>> {
        val answers = LinkedHashMap<String, List<String>>()
        for (question in questions) {
            val selected = picked[question.id]?.takeIf { it.isNotEmpty() } ?: continue
            answers[question.id] = selected.mapNotNull { question.options.getOrNull(it)?.label }
        }
        return answers
    }
}

/** What the inline form shows for one tool call, and how it answers. */
data class InlineQuestionForm(
    val questions: List<InlineQuestion>,
    val canInteract: Boolean,
    /** Set once answered: the summary replaces the form (an empty map shows "—"). */
    val submitted: Map<String, List<String>>?,
    val reply: Reply?,
) {
    sealed interface Reply {
        /** AskUserQuestion: allow the permission with the answers merged into the input. */
        data class Permission(val id: String) : Reply

        /** request_user_input: answer the communication. */
        data class Communication(val id: String, val kind: String) : Reply
    }

    companion object {
        /** The form a question tool shows, or null for any other tool. */
        fun of(tool: ToolCall, agentState: AgentState?): InlineQuestionForm? = when (tool.name) {
            "AskUserQuestion" -> askUserQuestion(tool)
            "request_user_input" -> requestUserInput(tool, selectAgentFormCommunication(agentState, tool.callId))
            else -> null
        }

        /**
         * AskUserQuestionView: questions from the input, all required; the form
         * is live while the tool runs, and a completed call shows the summary.
         */
        fun askUserQuestion(tool: ToolCall): InlineQuestionForm? {
            val raw = tool.input.obj?.get("questions").arr.orEmpty()
            val questions = raw.mapIndexed { index, question ->
                val o = question.obj
                InlineQuestion(
                    id = "question-$index",
                    question = o?.get("question").str ?: "",
                    header = o?.get("header").str ?: "",
                    options = o?.get("options").arr.orEmpty().mapNotNull { option ->
                        option.obj?.get("label").str?.let { AgentQuestionOption(it, option.obj?.get("description").str) }
                    },
                    multiSelect = o?.get("multiSelect").bool ?: false,
                    required = true,
                )
            }
            if (questions.isEmpty()) return null
            return InlineQuestionForm(
                questions = questions,
                canInteract = tool.state == ToolState.Running,
                submitted = if (tool.state == ToolState.Completed) emptyMap() else null,
                reply = tool.permission?.let { Reply.Permission(it.id) },
            )
        }

        /** The input AskUserQuestion's permission is allowed with: each question's text to its labels, comma-joined. */
        fun askUserQuestionInput(tool: ToolCall, answers: Map<String, List<String>>): JsonObject {
            val provider = LinkedHashMap<String, JsonElement>()
            tool.input.obj?.get("questions").arr.orEmpty().forEachIndexed { index, question ->
                val text = question.obj?.get("question").str ?: return@forEachIndexed
                val selected = answers["question-$index"]?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                provider[text] = JsonPrimitive(selected.joinToString(", "))
            }
            return JsonObject(mapOf("answers" to JsonObject(provider)))
        }

        /** RequestUserInputView: the communication the call raised. A form with a text-only question is left to the banner. */
        fun requestUserInput(tool: ToolCall, communication: AgentFormCommunication?): InlineQuestionForm? {
            if (communication == null || communication.questions.any { it.options.isEmpty() }) return null
            val submitted = if (communication.status == AgentFormStatus.Pending) {
                null
            } else {
                val answers = LinkedHashMap<String, List<String>>()
                if (communication.status == AgentFormStatus.Answered) {
                    communication.answers?.forEach { (id, answer) ->
                        answers[id] = answer.options + listOfNotNull(answer.custom?.takeIf { it.isNotEmpty() })
                    }
                }
                answers
            }
            return InlineQuestionForm(
                questions = communication.questions.map { InlineQuestion(it.id, it.question, it.header, it.options, it.multiSelect, it.required) },
                canInteract = tool.state == ToolState.Running && communication.status == AgentFormStatus.Pending,
                submitted = submitted,
                reply = Reply.Communication(communication.id, "form"),
            )
        }

        /** request_user_input's answers: the chosen labels, one for a single choice. */
        fun communicationAnswers(questions: List<InlineQuestion>, answers: Map<String, List<String>>): Map<String, AgentQuestionAnswer> {
            val result = LinkedHashMap<String, AgentQuestionAnswer>()
            for (question in questions) {
                val selected = answers[question.id]?.takeIf { it.isNotEmpty() } ?: continue
                result[question.id] = AgentQuestionAnswer(if (question.multiSelect) selected else selected.take(1))
            }
            return result
        }
    }
}
