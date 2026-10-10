package io.github.veritasx1.lichat.core

/** Rechnen im Textfeld (card bcb40e06, f028, like Math Notes in Messages): „87,60/4 =“ at the end of what is typed →
 *  the result is offered („21,9“), a tap puts it in. Only + − × ÷ : ^ %, brackets and numbers with comma or point – no
 *  names, nothing is ever run as code. The twin of Ubuntu's lichat/calc.py; both checked against docs/rechnen-faelle.json. */
private val TAIL = Regex("""(?<![\w.,])([-+(]*\d[\d,.]*(?:\s*[-+*/×÷:^%()]+\s*[\d,.()]*)*)\s*=\s*$""")
private val OPERATOR = Regex("""\d\s*[-+*/×÷:^%]\s*[\d(]|\d\s*%""")
private val PHONE = Regex("""\d{4,}-\d{4,}""")
private val TOKEN = Regex("""\d+(?:[.,]\d+)?|[-+*/×÷:^%()]""")
private const val LIMIT = 1e15

private class CalcParser(text: String) {
    val tokens = TOKEN.findAll(text.replace(" ", "")).map { it.value }.toList()
    var at = 0
    init { if (tokens.joinToString("") != text.replace(" ", "")) throw IllegalArgumentException("unbekannte Zeichen") }
    fun peek() = tokens.getOrNull(at)
    fun take() = tokens[at++]

    fun expression(): Double {
        var value = term()
        while (peek() == "+" || peek() == "-") {
            val sign = take()
            var right = term()
            if (peek() == "%") { take(); right = value * right / 100 }     // 200+15% = 230, like a calculator
            value = if (sign == "+") value + right else value - right
        }
        return value
    }

    fun term(): Double {
        var value = power()
        while (peek() in setOf("*", "/", "×", "÷", ":")) {
            val sign = take()
            val right = power()
            if (sign == "*" || sign == "×") value *= right
            else { if (right == 0.0) throw IllegalArgumentException("durch null"); value /= right }
        }
        return value
    }

    fun power(): Double {
        val base = unary()
        if (peek() == "^") {
            take()
            val exponent = power()
            if (kotlin.math.abs(exponent) > 64) throw IllegalArgumentException("zu groß")
            return Math.pow(base, exponent)
        }
        return base
    }

    fun unary(): Double = when (peek()) {
        "-" -> { take(); -unary() }
        "+" -> { take(); unary() }
        else -> atom()
    }

    fun atom(): Double {
        val token = peek()
        var value = when {
            token == "(" -> { take(); val v = expression(); if (peek() != ")") throw IllegalArgumentException("Klammer fehlt"); take(); v }
            token != null && token[0].isDigit() -> take().replace(',', '.').toDouble()
            else -> throw IllegalArgumentException("Zahl fehlt")
        }
        if (peek() == "%" && (at + 1 >= tokens.size || tokens[at + 1] !in setOf("+", "-"))) { take(); value /= 100 }
        return value
    }
}

/** 21.9 → „21,9“; at most ten places, no trailing zeros. */
fun german(value: Double): String {
    val text = java.math.BigDecimal(value).setScale(10, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return if (text == "-0" || text.isEmpty()) "0" else text.replace('.', ',')
}

/** The result for „… 87,60/4 =“ at the end of [text], or null (no sum, a phone number, an error). */
fun calcSuggestion(text: String): String? {
    val sum = TAIL.find(text)?.groupValues?.get(1) ?: return null
    if (!OPERATOR.containsMatchIn(sum) || PHONE.containsMatchIn(sum)) return null
    val value = try {
        val parser = CalcParser(sum)
        val v = parser.expression()
        if (parser.peek() != null) return null
        v
    } catch (_: IllegalArgumentException) { return null } catch (_: IndexOutOfBoundsException) { return null }
    if (value.isNaN() || value.isInfinite() || kotlin.math.abs(value) >= LIMIT) return null
    return german(value)
}
