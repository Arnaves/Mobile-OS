package com.axiom.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreTest {
    @Test fun routing() {
        val all = setOf("chat", "think", "code", "vision")
        assertEquals("code", Router.route("fix this python error", all))
        assertEquals("think", Router.route("compare these two phones", all))
        assertEquals("chat", Router.route("tell me a joke", all))
        assertEquals("chat", Router.route("write python", setOf("chat")))
        assertNull(Router.route("hi", emptySet()))
    }

    @Test fun commands() {
        assertEquals(Cmd.Open("youtube"), Commands.parse("Hey Jarvis, open YouTube".lowercase()))
        assertEquals(Cmd.Alarm(7, 30), Commands.parse("set an alarm for 7:30 am"))
        assertEquals(Cmd.Alarm(18, 0), Commands.parse("wake me up at 6 pm"))
        assertEquals(Cmd.Timer(600), Commands.parse("set a timer for 10 minutes"))
        assertEquals(Cmd.Torch(true), Commands.parse("turn on the flashlight"))
        assertEquals(Cmd.Torch(false), Commands.parse("torch off"))
        assertEquals(Cmd.Call("mom"), Commands.parse("please call mom"))
        assertEquals(Cmd.Navigate("the airport"), Commands.parse("take me to the airport"))
        assertEquals(Cmd.Play("lofi beats"), Commands.parse("play lofi beats"))
        assertEquals(Cmd.Time, Commands.parse("what time is it?"))
        assertEquals(Cmd.Battery, Commands.parse("battery"))
        assertNull(Commands.parse("explain how alarms work in android"))
        assertNull(Commands.parse("write a python script"))
    }

    @Test fun hfPick() {
        val files = listOf("README.md", "gemma-3-4b-it-Q4_K_M.gguf", "gemma-3-4b-it-Q8_0.gguf",
            "mmproj-model-bf16.gguf", "mmproj-model-f16.gguf")
        assertEquals("gemma-3-4b-it-Q4_K_M.gguf", HfPick.model(files, "Q4_K_M"))
        assertEquals("mmproj-model-f16.gguf", HfPick.mmproj(files))
        assertEquals("qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            HfPick.model(listOf("qwen2.5-coder-3b-instruct-q4_k_m.gguf", "qwen2.5-coder-3b-instruct-q4_k_m-00001-of-00002.gguf"), "Q4_K_M"))
    }

    @Test fun thinkTags() {
        val t = ThinkTagger()
        val out = StringBuilder()
        listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"hmm "}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"ok"}}]}""",
            """data: {"choices":[{"delta":{"content":"Answer"}}]}""",
            """data: {"choices":[{"delta":{"content":null}}]}""",
            "data: [DONE]",
        ).forEach { t.feed(it)?.let(out::append) }
        t.finish()?.let(out::append)
        assertEquals("<think>hmm ok</think>Answer", out.toString())
    }

    @Test fun remember() {
        assertEquals("I like tea", Memory.REMEMBER.find("Remember that I like tea")?.groupValues?.get(1))
        assertEquals("my car is blue", Memory.REMEMBER.find("jarvis, remember my car is blue")?.groupValues?.get(1))
    }
}
