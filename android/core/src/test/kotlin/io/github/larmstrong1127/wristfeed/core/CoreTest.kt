package io.github.larmstrong1127.wristfeed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val t0 = FeedParser.parseRfc3339("2026-11-16T06:40:00Z")!!

    private fun ok(json: String, fetchedAt: Long = t0): Feed {
        val r = FeedParser.parse(json, fetchedAt)
        assertTrue("expected Ok, got $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).feed
    }

    private val specExample = """
        {"wristfeed":1,"title":"JARVIS","status":"warn","kind":"stats",
         "rows":[{"label":"Apps","value":"1/5","status":"bad"},{"label":"Asks","value":"2"},
                 {"label":"Systems","value":"OK","status":"ok"}],
         "progress":{"label":"Apps","value":1,"max":5},"message":null,
         "updated":"2026-11-16T06:40:00Z","stale_after_minutes":360,
         "tap_url":"https://example.com/dashboard","extra":{"ignored":[1,2,3]}}
    """.trimIndent()

    @Test fun parsesSpecExample() {
        val f = ok(specExample)
        assertEquals("JARVIS", f.title)
        assertEquals(Status.WARN, f.status)
        assertEquals(Kind.STATS, f.kind)
        assertEquals(3, f.rows.size)
        assertEquals(Status.BAD, f.rows[0].status)
        assertNull(f.rows[1].status)
        assertEquals(Progress("Apps", 1.0, 5.0), f.progress)
        assertNull(f.message)
        assertEquals(360, f.staleAfterMinutes)
        assertEquals("https://example.com/dashboard", f.tapUrl)
        assertEquals(t0, f.updatedEpochMs)
    }

    @Test fun defaultsForMissingOptionals() {
        val f = ok("""{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z"}""")
        assertEquals(Status.IDLE, f.status)
        assertEquals(Kind.STATS, f.kind)
        assertTrue(f.rows.isEmpty())
        assertNull(f.progress)
        assertEquals(360, f.staleAfterMinutes)
        assertNull(f.tapUrl)
    }

    @Test fun wrongTypedOptionalsFallBack() {
        val f = ok(
            """{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","status":"purple","kind":"pie",
               "rows":"nope","progress":{"label":"x","value":"3","max":5},"stale_after_minutes":0,
               "tap_url":"http://insecure.example","message":42}"""
        )
        assertEquals(Status.IDLE, f.status)
        assertEquals(Kind.STATS, f.kind)
        assertTrue(f.rows.isEmpty())
        assertNull(f.progress)
        assertEquals(360, f.staleAfterMinutes)
        assertNull(f.tapUrl)
        assertNull(f.message)
    }

    @Test fun fallbackFlag() {
        val clean = FeedParser.parse(specExample, t0) as ParseResult.Ok
        assertTrue(!clean.fallback)
        val bad = FeedParser.parse("""{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","status":"great"}""", t0) as ParseResult.Ok
        assertTrue(bad.fallback)
    }

    @Test fun progressMaxZeroIsDroppedAndValueClamped() {
        assertNull(ok("""{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","progress":{"label":"a","value":1,"max":0}}""").progress)
        assertEquals(5.0, ok("""{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","progress":{"label":"a","value":9,"max":5}}""").progress!!.value, 0.0)
        assertEquals(0.0, ok("""{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","progress":{"label":"a","value":-2,"max":5}}""").progress!!.value, 0.0)
    }

    @Test fun missingRequiredIsMalformed() {
        for (doc in listOf(
            """{"title":"T","updated":"2026-11-16T06:40:00Z"}""",
            """{"wristfeed":1,"updated":"2026-11-16T06:40:00Z"}""",
            """{"wristfeed":1,"title":"T"}""",
            """{"wristfeed":1,"title":7,"updated":"2026-11-16T06:40:00Z"}""",
            """{"wristfeed":1,"title":"  ","updated":"2026-11-16T06:40:00Z"}""",
            """{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00"}""",
            """{"wristfeed":1.0,"title":"T","updated":"2026-11-16T06:40:00Z"}""",
            """{"wristfeed":1,"title":"T","updated":"yesterday"}""",
            """{"wristfeed":"1","title":"T","updated":"2026-11-16T06:40:00Z"}""",
            """[1,2]""",
            """{"wristfeed":1,""",
            "",
        )) {
            assertTrue("should be malformed: $doc", FeedParser.parse(doc, t0) is ParseResult.Malformed)
        }
    }

    @Test fun otherVersionIsUnsupported() {
        val r = FeedParser.parse("""{"wristfeed":2,"title":"T","updated":"2026-11-16T06:40:00Z"}""", t0)
        assertEquals(ParseResult.Unsupported(2L), r)
    }

    @Test fun over64KbIsMalformed() {
        val pad = "x".repeat(FeedParser.MAX_BYTES)
        val doc = """{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","pad":"$pad"}"""
        assertTrue(FeedParser.parse(doc, t0) is ParseResult.Malformed)
    }

    @Test fun offsetTimestampsParse() {
        val f = ok("""{"wristfeed":1,"title":"T","updated":"2026-11-15T22:40:00-08:00"}""")
        assertEquals(t0, f.updatedEpochMs)
    }

    @Test fun clockSkewGuard() {
        val future = """{"wristfeed":1,"title":"T","updated":"2026-11-16T07:40:00Z"}"""
        assertEquals(t0, ok(future, fetchedAt = t0).updatedEpochMs) // 60 min ahead -> download time
        val slight = """{"wristfeed":1,"title":"T","updated":"2026-11-16T06:41:00Z"}"""
        assertEquals(t0 + 60_000, ok(slight, fetchedAt = t0).updatedEpochMs) // 1 min ahead -> kept
    }

    @Test fun states() {
        val f = ok(specExample)
        assertEquals(FeedState.Loading, StateMachine.resolve(null, FetchOutcome.NONE, null, t0))
        assertEquals(FeedState.Unreachable, StateMachine.resolve(null, FetchOutcome.UNREACHABLE, null, t0))
        assertEquals(FeedState.Malformed("x"), StateMachine.resolve(null, FetchOutcome.MALFORMED, "x", t0))
        assertEquals(FeedState.Unsupported, StateMachine.resolve(null, FetchOutcome.UNSUPPORTED, null, t0))
        assertTrue(StateMachine.resolve(f, FetchOutcome.OK, null, t0 + 359 * 60_000L) is FeedState.Fresh)
        assertTrue(StateMachine.resolve(f, FetchOutcome.OK, null, t0 + 360 * 60_000L) is FeedState.Stale)
        // A failure after a good copy keeps the copy.
        assertTrue(StateMachine.resolve(f, FetchOutcome.UNREACHABLE, null, t0) is FeedState.Fresh)
        assertTrue(StateMachine.resolve(f, FetchOutcome.MALFORMED, "x", t0) is FeedState.Fresh)
    }

    @Test fun refreshOnVisible() {
        assertTrue(StateMachine.shouldRefreshOnVisible(null, t0))
        assertTrue(!StateMachine.shouldRefreshOnVisible(t0, t0 + 15 * 60_000L))
        assertTrue(StateMachine.shouldRefreshOnVisible(t0, t0 + 15 * 60_000L + 1))
    }

    @Test fun truncation() {
        assertEquals("abc", Display.truncate("abc", 3))
        assertEquals("ab…", Display.truncate("abcd", 3))
        assertEquals(24, Display.title(ok("""{"wristfeed":1,"title":"${"T".repeat(40)}","updated":"2026-11-16T06:40:00Z"}""")).length)
        val r = Display.shownRows(ok("""{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z",
            "rows":[{"label":"ABCDEFGHIJKLMN","value":"12345678901"},{"label":"b","value":"2"},{"label":"c","value":"3"},{"label":"d","value":"4"}]}"""))
        assertEquals(3, r.size)
        assertEquals("ABCDEFGHIJK…", r[0].label)
        assertEquals("123456789…", r[0].value)
    }

    @Test fun ageFormatting() {
        assertEquals("0m ago", Display.age(-5))
        assertEquals("59m ago", Display.age(59 * 60_000L + 59_000))
        assertEquals("1h ago", Display.age(60 * 60_000L))
        assertEquals("Old data · 7h ago", Display.staleFooter(7 * 3_600_000L + 1))
    }

    @Test fun complicationText() {
        val f = ok(specExample)
        assertEquals("1/5", Display.shortText(f))
        assertEquals("JARVIS", Display.shortTitle(f))
        assertEquals("JARVIS: Apps 1/5 · Asks 2 · Systems OK", Display.longText(f))
        assertEquals("1/5", Display.shortText(f.copy(kind = Kind.PROGRESS)))
        val m = f.copy(kind = Kind.MESSAGE, message = "All quiet")
        assertEquals("JARVIS", Display.shortText(m))
        assertEquals("All quiet", Display.longText(m))
    }

    @Test fun shortTextIsCutToSevenCharacters() {
        val f = ok(specExample).copy(title = "Household chores", rows = listOf(Row("A", "123456789", null)))
        assertEquals("123456\u2026", Display.shortText(f))
        assertEquals("Househo".dropLast(1) + "\u2026", Display.shortTitle(f))
        assertEquals(7, Display.shortTitle(f).length)
        val p = f.copy(kind = Kind.PROGRESS, progress = Progress("A", 1234.5, 99999.0))
        assertEquals("1234.5\u2026", Display.shortText(p))
        // LONG_TEXT is not cut to 7.
        assertTrue(Display.longText(f).startsWith("Household chores: "))
    }

    private fun outcome(json: String): String = when (FeedParser.parse(json, Long.MAX_VALUE)) {
        is ParseResult.Ok -> "ok"
        is ParseResult.Malformed -> "malformed"
        is ParseResult.Unsupported -> "unsupported"
    }

    @Test fun hostileNumbersFallBack() {
        for (n in listOf("1e400", "-1e400", "1" + "0".repeat(400), "9".repeat(5000), "1.5e308")) {
            val f = ok(
                """{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","kind":"progress",""" +
                    """"progress":{"label":"A","value":1,"max":$n},"stale_after_minutes":$n}""",
            )
            assertNull(n.take(10), f.progress)
            assertEquals(360, f.staleAfterMinutes)
        }
    }

    @Test fun nanInfinityAndDepth() {
        val head = """{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","x":"""
        for (c in listOf("NaN", "Infinity", "-Infinity")) assertEquals(c, "malformed", outcome("$head$c}"))
        assertEquals("ok", outcome(head + "[".repeat(31) + "]".repeat(31) + "}"))
        assertEquals("malformed", outcome(head + "[".repeat(32) + "]".repeat(32) + "}"))
        assertEquals("malformed", outcome(head + "[".repeat(20000) + "]".repeat(20000) + "}"))
    }

    @Test fun versionAndStaleRanges() {
        val tail = ""","title":"T","updated":"2026-11-16T06:40:00Z"}"""
        assertEquals("unsupported", outcome("""{"wristfeed":9007199254740992$tail"""))
        assertEquals("unsupported", outcome("""{"wristfeed":-9007199254740992$tail"""))
        assertEquals("malformed", outcome("""{"wristfeed":9007199254740993$tail"""))
        assertEquals("malformed", outcome("""{"wristfeed":-9007199254740993$tail"""))
        assertEquals("malformed", outcome("""{"wristfeed":100000000000000000000$tail"""))
        val base = """{"wristfeed":1,"title":"T","updated":"2026-11-16T06:40:00Z","stale_after_minutes":"""
        assertEquals(525600, ok("${base}525600}").staleAfterMinutes)
        assertEquals(360, ok("${base}525601}").staleAfterMinutes)
        assertEquals(360, ok("${base}0}").staleAfterMinutes)
    }

    @Test fun timestampFractionAndOffsetLimits() {
        assertTrue(FeedParser.parseRfc3339("2026-11-16T06:40:00.123456789Z") != null)
        assertNull(FeedParser.parseRfc3339("2026-11-16T06:40:00.1234567891Z"))
        assertTrue(FeedParser.parseRfc3339("2026-11-16T06:40:00+18:00") != null)
        assertTrue(FeedParser.parseRfc3339("2026-11-16T06:40:00-18:00") != null)
        assertNull(FeedParser.parseRfc3339("2026-11-16T06:40:00+18:01"))
        assertNull(FeedParser.parseRfc3339("2026-11-16T06:40:00+23:59"))
        assertNull(FeedParser.parseRfc3339("2026-11-16T06:40:00+05:60"))
    }

    @Test fun jsonReader() {
        assertEquals(mapOf("a" to listOf(1L, 2.5, true, null, "x\"é")), MiniJson.parse("""{"a":[1,2.5,true,null,"x\"é"]}"""))
        assertEquals(1.0, MiniJson.parse("1.0"))
    }
}
