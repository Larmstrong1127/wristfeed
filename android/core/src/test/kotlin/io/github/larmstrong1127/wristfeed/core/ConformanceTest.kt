package io.github.larmstrong1127.wristfeed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Runs every pair in the shared corpus at <repo>/conformance/ (format: conformance/README.md):
 * `<name>.json` is the raw body (read as bytes), `<name>.expected.json` holds
 * `{"outcome": "ok"|"malformed"|"unsupported", "fallback": bool, "feed": {...normalized...}}`.
 *
 * The repo root comes from the `wristfeed.repoRoot` system property (set in core/build.gradle.kts),
 * falling back to walking up from the working directory until SPEC.md is found.
 * New pairs are picked up automatically. If the folder is missing, the test fails loudly rather
 * than passing on an empty corpus.
 */
class ConformanceTest {
    private fun repoRoot(): File {
        System.getProperty("wristfeed.repoRoot")?.let { File(it) }?.takeIf { File(it, "SPEC.md").exists() }?.let { return it }
        var d: File? = File("").absoluteFile
        while (d != null) {
            if (File(d, "SPEC.md").exists() && File(d, "conformance").isDirectory) return d
            d = d.parentFile
        }
        fail("could not find the repo root (a folder with SPEC.md and conformance/)")
        throw IllegalStateException()
    }

    private val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    @Test fun corpus() {
        val dir = File(repoRoot(), "conformance")
        val cases = dir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".expected.json") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("conformance corpus is empty at $dir", cases.isNotEmpty())

        val failures = ArrayList<String>()
        for (body in cases) {
            val name = body.name.removeSuffix(".json")
            val expectedFile = File(dir, "$name.expected.json")
            if (!expectedFile.exists()) { failures += "$name: no .expected.json"; continue }
            val expected = MiniJson.parse(expectedFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")) as Map<*, *>
            // Long.MAX_VALUE disables the clock-skew guard: the corpus has no download time.
            val result = FeedParser.parse(body.readBytes(), Long.MAX_VALUE)
            try {
                check(name, expected, result)
            } catch (e: AssertionError) {
                failures += "$name: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} cases failed:\n" + failures.joinToString("\n"))
    }

    /** SPEC "Number display": the same table drives publisher/tests/test_display.py. */
    @Test fun numberDisplayVectors() {
        val file = File(repoRoot(), "conformance/display/numbers.json")
        val doc = MiniJson.parse(file.readText(Charsets.UTF_8).removePrefix("\uFEFF")) as Map<*, *>
        val cases = doc["cases"] as List<*>
        assertTrue("too few number vectors", cases.size >= 10)
        val failures = ArrayList<String>()
        for (c in cases) {
            c as Map<*, *>
            val input = (c["input"] as Number).toDouble()
            val got = Display.formatNumber(input)
            if (got != c["display"]) failures += "$input: expected ${c["display"]}, got $got"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun check(name: String, expected: Map<*, *>, result: ParseResult) {
        val outcome = when (result) {
            is ParseResult.Ok -> "ok"
            is ParseResult.Malformed -> "malformed"
            is ParseResult.Unsupported -> "unsupported"
        }
        assertEquals("outcome", expected["outcome"], outcome)
        if (result !is ParseResult.Ok) return
        expected["fallback"]?.let { assertEquals("fallback", it, result.fallback) }
        val e = expected["feed"] as? Map<*, *> ?: return
        val f = result.feed
        assertEquals("title", e["title"], f.title)
        assertEquals("updated", e["updated"], utc.format(Instant.ofEpochMilli(f.updatedEpochMs)))
        assertEquals("status", e["status"], f.status.name.lowercase())
        assertEquals("kind", e["kind"], f.kind.name.lowercase())
        val rows = (e["rows"] as List<*>).map { r ->
            r as Map<*, *>
            Row(r["label"] as String, r["value"] as String, (r["status"] as String?)?.let { Status.valueOf(it.uppercase()) })
        }
        assertEquals("rows", rows, f.rows)
        val p = e["progress"] as Map<*, *>?
        if (p == null) {
            assertEquals("progress", null, f.progress)
        } else {
            val fp = f.progress ?: throw AssertionError("progress missing")
            assertEquals("progress.label", p["label"], fp.label)
            assertEquals("progress.value", (p["value"] as Number).toDouble(), fp.value, 1e-9)
            assertEquals("progress.max", (p["max"] as Number).toDouble(), fp.max, 1e-9)
        }
        assertEquals("message", e["message"], f.message)
        assertEquals("stale_after_minutes", (e["stale_after_minutes"] as Number).toInt(), f.staleAfterMinutes)
        assertEquals("tap_url", e["tap_url"], f.tapUrl)
        // Dashboard fields. Every expectation carries them (null when absent).
        if (!e.containsKey("headline") || !e.containsKey("alert")) throw AssertionError("expectation lacks headline/alert")
        assertEquals("headline", e["headline"], f.headline)
        val a = e["alert"] as Map<*, *>?
        val got = f.alert?.let { mapOf("text" to it.text, "status" to it.status.name.lowercase()) }
        assertEquals("alert", a?.let { mapOf("text" to it["text"], "status" to it["status"]) }, got)
    }
}
