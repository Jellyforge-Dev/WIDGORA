package io.github.jellyforgedev.widgora.core.integration.kuma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsTextParserTest {

    @Test
    fun commentsAndBlankLinesAreNotSamples() {
        assertNull(MetricsTextParser.parseLine("# HELP monitor_status Monitor Status"))
        assertNull(MetricsTextParser.parseLine("# TYPE monitor_status gauge"))
        assertNull(MetricsTextParser.parseLine(""))
        assertNull(MetricsTextParser.parseLine("   "))
    }

    @Test
    fun readsASampleWithoutLabels() {
        val line = MetricsTextParser.parseLine("process_open_fds 42")
        assertEquals("process_open_fds", line?.name)
        assertEquals(emptyMap(), line?.labels)
        assertEquals(42.0, line?.value)
    }

    @Test
    fun readsLabelsInTheOrderTheyAppear() {
        val line = MetricsTextParser.parseLine(
            "monitor_uptime_ratio{tag_01=\"\",monitor_id=\"3\",window=\"30d\"} 0.5",
        )
        assertEquals(listOf("tag_01", "monitor_id", "window"), line?.labels?.keys?.toList())
        assertEquals("3", line?.labels?.get("monitor_id"))
        assertEquals("30d", line?.labels?.get("window"))
        assertEquals(0.5, line?.value)
    }

    @Test
    fun aCommaInsideAQuotedValueIsNotASeparator() {
        val line = MetricsTextParser.parseLine("m{a=\"x,y\",b=\"z\"} 1")
        assertEquals("x,y", line?.labels?.get("a"))
        assertEquals("z", line?.labels?.get("b"))
    }

    @Test
    fun anEqualsSignInsideAQuotedValueIsKept() {
        val line = MetricsTextParser.parseLine("m{a=\"k=v\"} 1")
        assertEquals("k=v", line?.labels?.get("a"))
    }

    @Test
    fun unescapesTheThreeDefinedEscapes() {
        val line = MetricsTextParser.parseLine("m{a=\"say \\\"hi\\\"\",b=\"c\\\\d\",c=\"e\\nf\"} 1")
        assertEquals("say \"hi\"", line?.labels?.get("a"))
        assertEquals("c\\d", line?.labels?.get("b"))
        assertEquals("e\nf", line?.labels?.get("c"))
    }

    @Test
    fun acceptsATrailingCommaBeforeTheClosingBrace() {
        val line = MetricsTextParser.parseLine("m{a=\"1\",} 2")
        assertEquals(mapOf("a" to "1"), line?.labels)
        assertEquals(2.0, line?.value)
    }

    @Test
    fun acceptsAnEmptyLabelSet() {
        val line = MetricsTextParser.parseLine("m{} 1")
        assertEquals(emptyMap(), line?.labels)
    }

    @Test
    fun acceptsAndDiscardsAnOptionalTimestamp() {
        val line = MetricsTextParser.parseLine("m{a=\"1\"} 2 1700000000000")
        assertEquals(2.0, line?.value)
    }

    @Test
    fun readsTheSpecialNumericTokens() {
        assertEquals(Double.POSITIVE_INFINITY, MetricsTextParser.parseLine("m +Inf")?.value)
        assertEquals(Double.NEGATIVE_INFINITY, MetricsTextParser.parseLine("m -Inf")?.value)
        assertTrue(MetricsTextParser.parseLine("m NaN")?.value?.isNaN() == true)
    }

    @Test
    fun readsNegativeAndExponentialValues() {
        assertEquals(-1.0, MetricsTextParser.parseLine("monitor_response_time{a=\"1\"} -1")?.value)
        assertEquals(1.5e-3, MetricsTextParser.parseLine("m 1.5e-3")?.value)
    }

    @Test
    fun rejectsASampleWithoutAValue() {
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m{a=\"1\"}") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m") }
    }

    @Test
    fun rejectsATruncatedLabelSet() {
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m{a=\"1\" 2") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m{a=\"1") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m{a=") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m{a") }
    }

    @Test
    fun rejectsAnUnquotedLabelValue() {
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m{a=1} 2") }
    }

    @Test
    fun rejectsADuplicateLabelName() {
        assertFailsWith<MalformedMetricsException> {
            MetricsTextParser.parseLine("m{a=\"1\",a=\"2\"} 3")
        }
    }

    @Test
    fun rejectsAnUndefinedEscape() {
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m{a=\"x\\ty\"} 1") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m{a=\"x\\\"} 1") }
    }

    @Test
    fun rejectsAMissingMetricName() {
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("{a=\"1\"} 2") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("1abc 2") }
    }

    /**
     * A corrupted token must not pass as a number. The platform number parser would accept
     * both of these, which is why the parser validates the token itself.
     */
    @Test
    fun rejectsNumberFormatsTheExpositionFormatDoesNotDefine() {
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m 1d") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m 0x10") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m Infinity") }
    }

    @Test
    fun rejectsContentAfterTheTimestamp() {
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m 1 2 3") }
        assertFailsWith<MalformedMetricsException> { MetricsTextParser.parseLine("m 1 abc") }
    }
}
