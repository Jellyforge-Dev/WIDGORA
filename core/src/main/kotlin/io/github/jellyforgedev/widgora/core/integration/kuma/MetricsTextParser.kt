package io.github.jellyforgedev.widgora.core.integration.kuma

/** One sample line of a metrics response: name, labels and value. */
internal data class MetricLine(
    val name: String,
    val labels: Map<String, String>,
    val value: Double,
)

internal class MalformedMetricsException(message: String) : Exception(message)

/**
 * Reads the subset of the Prometheus text exposition format that the metrics endpoint emits.
 *
 * It is strict on purpose. A line it cannot account for ends the whole read instead of being
 * skipped, because a half transmitted response that silently loses lines would under-report
 * failing monitors and present an outage as a healthy system.
 *
 * The label set is not fixed: user-defined tags appear as additional labels whose names vary
 * per installation, so labels are read into a map rather than matched against a known list.
 */
internal object MetricsTextParser {

    /** @return the parsed line, or `null` for a blank line or a comment. */
    fun parseLine(line: String): MetricLine? {
        val text = line.trim()
        if (text.isEmpty() || text[0] == '#') return null

        var index = 0
        while (index < text.length && isNameChar(text[index], first = index == 0)) index++
        val name = text.substring(0, index)
        if (name.isEmpty()) fail("metric name missing")

        val labels = if (index < text.length && text[index] == '{') {
            val collected = LinkedHashMap<String, String>()
            index = parseLabels(text, index + 1, collected)
            collected
        } else {
            emptyMap()
        }

        if (index >= text.length || !text[index].isWhitespace()) fail("value missing")
        index = skipWhitespace(text, index)
        val valueStart = index
        index = skipToken(text, index)
        val value = parseValue(text.substring(valueStart, index))

        index = skipWhitespace(text, index)
        if (index < text.length) {
            // An optional timestamp is part of the format. It is validated so that a truncated
            // line is still rejected, then discarded: the endpoint does not emit one, and the
            // age of the data is the time of retrieval.
            val timestampStart = index
            index = skipToken(text, index)
            val timestamp = text.substring(timestampStart, index)
            if (!TIMESTAMP.matches(timestamp)) fail("timestamp is not an integer")
            index = skipWhitespace(text, index)
            if (index < text.length) fail("unexpected trailing content")
        }

        return MetricLine(name = name, labels = labels, value = value)
    }

    private fun parseLabels(text: String, from: Int, into: MutableMap<String, String>): Int {
        var index = skipWhitespace(text, from)
        if (index < text.length && text[index] == '}') return index + 1

        while (true) {
            index = skipWhitespace(text, index)
            val keyStart = index
            while (index < text.length && isNameChar(text[index], first = index == keyStart)) index++
            val key = text.substring(keyStart, index)
            if (key.isEmpty()) fail("label name missing")

            index = skipWhitespace(text, index)
            if (index >= text.length || text[index] != '=') fail("label separator missing")
            index = skipWhitespace(text, index + 1)
            if (index >= text.length || text[index] != '"') fail("label value is not quoted")
            index++

            val value = StringBuilder()
            while (true) {
                if (index >= text.length) fail("label value is not terminated")
                val character = text[index]
                when {
                    character == '\\' -> {
                        if (index + 1 >= text.length) fail("label value ends in an escape")
                        value.append(unescape(text[index + 1]))
                        index += 2
                    }
                    character == '"' -> {
                        index++
                        break
                    }
                    else -> {
                        value.append(character)
                        index++
                    }
                }
            }
            if (into.put(key, value.toString()) != null) fail("duplicate label name")

            index = skipWhitespace(text, index)
            if (index >= text.length) fail("label set is not terminated")
            when (text[index]) {
                ',' -> index++
                '}' -> return index + 1
                else -> fail("unexpected character in label set")
            }
            index = skipWhitespace(text, index)
            if (index < text.length && text[index] == '}') return index + 1
        }
    }

    private fun parseValue(token: String): Double = when (token) {
        "+Inf", "Inf" -> Double.POSITIVE_INFINITY
        "-Inf" -> Double.NEGATIVE_INFINITY
        "NaN" -> Double.NaN
        // Not String.toDouble: that also accepts hexadecimal literals and a trailing type
        // suffix, which would turn a corrupted token into a plausible number.
        else -> if (NUMBER.matches(token)) token.toDouble() else fail("value is not a number")
    }

    private fun unescape(escaped: Char): Char = when (escaped) {
        '\\' -> '\\'
        '"' -> '"'
        'n' -> '\n'
        else -> fail("unsupported escape in label value")
    }

    private fun isNameChar(character: Char, first: Boolean): Boolean {
        val letter = character in 'a'..'z' || character in 'A'..'Z' ||
            character == '_' || character == ':'
        return letter || (!first && character in '0'..'9')
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun skipToken(text: String, from: Int): Int {
        var index = from
        while (index < text.length && !text[index].isWhitespace()) index++
        return index
    }

    private fun fail(reason: String): Nothing = throw MalformedMetricsException(reason)

    private val NUMBER = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$""")
    private val TIMESTAMP = Regex("""^[+-]?\d+$""")
}
