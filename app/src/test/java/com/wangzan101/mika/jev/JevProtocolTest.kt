package com.wangzan101.mika.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JevProtocolTest {
    @Test fun acceptsOnlyTheChildProcessLoopbackListeningLine() {
        assertEquals(45678, JevProtocol.listeningPort("srv listening: listening on http://127.0.0.1:45678"))
        assertNull(JevProtocol.listeningPort("listening on http://0.0.0.0:45678"))
        assertNull(JevProtocol.listeningPort("listening on http://127.0.0.1:99999"))
        assertNull(JevProtocol.listeningPort("http://127.0.0.1:45678"))
    }
    @Test fun fixedClassificationRequestPreservesTextAndImageOrder() {
        val text = "第一行\n\"quoted\" <test>"
        val root = Json.parseToJsonElement(JevProtocol.request(text, "ORIGINAL", "RENDERED")).jsonObject
        assertEquals(text, root.getValue("state").jsonPrimitive.content)
        assertEquals(listOf("data:image/png;base64,ORIGINAL", "data:image/png;base64,RENDERED"), root.getValue("images").jsonArray.map { it.jsonPrimitive.content })
        val question = root.getValue("questions").jsonObject.getValue("ocr").jsonObject
        assertEquals("noul", question.getValue("type").jsonPrimitive.content)
        assertFalse(question.containsKey("instructions"))
        assertFalse(question.containsKey("criteria"))
    }

    @Test fun acceptsBothBoundaryScores() {
        for (score in listOf(0.0, 0.713, 1.0)) {
            assertEquals(score, JevProtocol.score("""{"answers":{"ocr":{"noul":$score}}}"""), 0.000001)
        }
    }

    @Test fun malformedResponsesNeverBecomeALowScore() {
        for (body in listOf("{}", "not json", """{"answers":{"ocr":{"noul":null}}}""",
            """{"answers":{"ocr":{"noul":-0.1}}}""", """{"answers":{"ocr":{"noul":1.1}}}""",
            """{"answers":{"ocr":{"noul":"NaN"}}}""", """{"answers":{"ocr":{"noul":"Infinity"}}}""",
            """{"error":"failed","answers":{"ocr":{"noul":0}}}""")) {
            assertThrows(Exception::class.java) { JevProtocol.score(body) }
        }
    }

    @Test fun rejectsMissingOrOversizedInputs() {
        assertThrows(IllegalArgumentException::class.java) { JevProtocol.request(" ", "a", "b") }
        assertThrows(IllegalArgumentException::class.java) { JevProtocol.request("x".repeat(1001), "a", "b") }
        assertThrows(IllegalArgumentException::class.java) { JevProtocol.request("x", "", "b") }
    }
}
