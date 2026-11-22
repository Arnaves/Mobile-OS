package com.axiom.jarvis

import org.json.JSONObject

/** One specialist model. Files are resolved on the phone via the Hugging Face API. */
data class Brain(
    val id: String,
    val title: String,
    val blurb: String,
    val repo: String,
    val quant: String,
    val approxGb: Double,
    val vision: Boolean = false,
)

object Brains {
    val all = listOf(
        Brain("chat", "Chat", "Fast everyday assistant", "unsloth/Qwen3-4B-Instruct-2507-GGUF", "Q4_K_M", 2.5),
        Brain("think", "Think", "Deep reasoning with visible thought", "unsloth/Qwen3-4B-Thinking-2507-GGUF", "Q4_K_M", 2.5),
        Brain("code", "Code", "Writes and explains code", "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF", "Q4_K_M", 2.1),
        Brain("vision", "Vision", "Sees through your camera", "ggml-org/gemma-3-4b-it-GGUF", "Q4_K_M", 3.3, vision = true),
    )
    fun get(id: String) = all.firstOrNull { it.id == id }
}

object Router {
    private val routes = listOf(
        "code" to Regex("\\b(code|coding|python|bash|script|function|bug|error|compile|regex|sql|javascript|kotlin|java|html|css|api|program)\\b", RegexOption.IGNORE_CASE),
        "think" to Regex("\\b(why|prove|plan|strategy|compare|decide|calculate|solve|math|step by step|reason|pros and cons|analy[sz]e)\\b", RegexOption.IGNORE_CASE),
    )

    /** Best brain for [prompt] among [installed]; null if nothing is installed. */
    fun route(prompt: String, installed: Set<String>): String? {
        val wanted = routes.firstOrNull { it.second.containsMatchIn(prompt) }?.first ?: "chat"
        if (wanted in installed) return wanted
        return listOf("chat", "think", "code", "vision").firstOrNull { it in installed }
    }
}

object HfPick {
    fun model(files: List<String>, quant: String): String? =
        files.filter { it.endsWith(".gguf", true) && !it.contains("mmproj", true) && it.contains(quant, true) }
            .minByOrNull { it.length }

    fun mmproj(files: List<String>): String? =
        files.filter { it.endsWith(".gguf", true) && it.contains("mmproj", true) }
            .sortedBy {
                when {
                    it.contains("f16", true) && !it.contains("bf16", true) -> 0
                    it.contains("q8", true) -> 1
                    else -> 2
                }
            }.firstOrNull()
}

/** Turns llama-server's separate reasoning/content deltas into one stream with <think> tags. */
class ThinkTagger {
    private var inThink = false

    /** Parse one SSE line; returns text to show, or null. */
    fun feed(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("data:") || t.endsWith("[DONE]")) return null
        val choices = JSONObject(t.substring(5).trim()).optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
        val reasoning = delta.optString("reasoning_content", "").takeUnless { delta.isNull("reasoning_content") } ?: ""
        val content = delta.optString("content", "").takeUnless { delta.isNull("content") } ?: ""
        val sb = StringBuilder()
        if (reasoning.isNotEmpty()) {
            if (!inThink) { sb.append("<think>"); inThink = true }
            sb.append(reasoning)
        }
        if (content.isNotEmpty()) {
            if (inThink) { sb.append("</think>"); inThink = false }
            sb.append(content)
        }
        return sb.toString().ifEmpty { null }
    }

    fun finish(): String? = if (inThink) { inThink = false; "</think>" } else null
}

/** Phone actions AXIOM performs directly, without waking a model. */
sealed class Cmd {
    data class Open(val app: String) : Cmd()
    data class Call(val who: String) : Cmd()
    data class Alarm(val hour: Int, val minute: Int) : Cmd()
    data class Timer(val seconds: Int) : Cmd()
    data class Torch(val on: Boolean) : Cmd()
    data class Search(val query: String) : Cmd()
    data class Navigate(val place: String) : Cmd()
    data class Play(val query: String) : Cmd()
    object Time : Cmd()
    object Battery : Cmd()
}

object Commands {
    private val o = RegexOption.IGNORE_CASE
    private val wake = Regex("^(hey |ok |okay )?(axiom|jarvis)[,!.]?\\s*", o)
    private val polite = Regex("^(please |can you |could you |would you )+", o)

    fun clean(raw: String) = raw.trim().replace(wake, "").replace(polite, "").trim().trimEnd('.', '!', '?').trim()

    fun parse(raw: String): Cmd? {
        val s = clean(raw)
        Regex("^(?:turn|switch) (on|off) (?:the )?(?:flash ?light|torch)$", o).find(s)?.let { return Cmd.Torch(it.groupValues[1].equals("on", true)) }
        Regex("^(?:turn |switch )?(?:the )?(?:flash ?light|torch) (on|off)$", o).find(s)?.let { return Cmd.Torch(it.groupValues[1].equals("on", true)) }
        Regex("^(?:open|launch|start) (?:the )?(.+?)(?: app)?$", o).find(s)?.let { return Cmd.Open(it.groupValues[1]) }
        Regex("^(?:call|phone|dial|ring) (.+)$", o).find(s)?.let { return Cmd.Call(it.groupValues[1]) }
        Regex("^(?:set )?(?:a |an )?timer (?:for )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)$", o).find(s)?.let {
            val n = it.groupValues[1].toInt()
            val u = it.groupValues[2].lowercase()
            return Cmd.Timer(n * when { u.startsWith("h") -> 3600; u.startsWith("m") -> 60; else -> 1 })
        }
        Regex("^(?:set (?:an |the |my )?alarm|wake me(?: up)?|alarm)(?: for| at)? (\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm|a\\.m|p\\.m)?$", o).find(s)?.let {
            var h = it.groupValues[1].toInt()
            val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = it.groupValues[3].lowercase()
            if (ap.startsWith("p") && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            if (h in 0..23 && m in 0..59) return Cmd.Alarm(h, m)
        }
        Regex("^(?:navigate|directions|take me|drive) to (.+)$", o).find(s)?.let { return Cmd.Navigate(it.groupValues[1]) }
        Regex("^(?:search|google|look up)(?: for)? (.+)$", o).find(s)?.let { return Cmd.Search(it.groupValues[1]) }
        Regex("^play (.+)$", o).find(s)?.let { return Cmd.Play(it.groupValues[1]) }
        if (Regex("^what(?:'s| is) the time(?: now)?$|^what time is it$", o).matches(s)) return Cmd.Time
        if (Regex("^(?:what(?:'s| is) (?:my |the )?)?battery(?: level| status)?$|^how much battery(?: do i have| is left)?$", o).matches(s)) return Cmd.Battery
        return null
    }
}

object Memory {
    val REMEMBER = Regex("^\\s*(?:hey |ok )?(?:axiom|jarvis)?[,]?\\s*remember(?: that)?[:,]?\\s+(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
}
