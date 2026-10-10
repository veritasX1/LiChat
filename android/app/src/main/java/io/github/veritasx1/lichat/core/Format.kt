package io.github.veritasx1.lichat.core

/** Text formatting in a message (card bcb40e06, f005–f011), the way it is typed on every keyboard, like WhatsApp:
 *  *fett*  _kursiv_  __unterstrichen__  ~durchgestrichen~  `Code`  ```Block```  ||Spoiler||  [Titel](https://…)
 *  and at the start of a line „> “ for a quote, „- “/„* “/„• “ for a list. The text travels as typed – an older LiChat
 *  shows the signs. The twin of Ubuntu's lichat/formatting.py; both are checked against docs/formatierung-faelle.json.
 *  Offsets are in UTF-16 (what Compose needs). */
/** One line for the list and notifications: without the signs, a spoiler as „•••“ – it never shows there. */
fun previewText(text: String): String {
    val (plain, spans) = formatted(text)
    val hidden = spans.filter { it.kind == "spoiler" }.sortedByDescending { it.start }
    return hidden.fold(plain) { shown, s -> shown.substring(0, s.start) + "•••" + shown.substring(s.end) }
        .trim().split(Regex("\\s+")).joinToString(" ")      // one line – quotes and lists too (Michelle)
}

data class FormatSpan(val start: Int, val end: Int, val kind: String, val target: String? = null)

private val PAIRS = listOf("||" to "spoiler", "__" to "unter", "**" to "fett", "*" to "fett", "_" to "kursiv", "~" to "durch")
private val TITLED = Regex("""\[([^\[\]\n]+)]\((https?://[^\s()]+|mailto:[^\s()]+)\)""")
private val BLOCK = Regex("""```\n?([\s\S]+?)\n?```""")
private val INLINE = Regex("""`([^`\n]+)`""")

private class Ch(val cp: Int, val kinds: MutableSet<String> = mutableSetOf(), var target: String? = null)

/** Around a sign: the start or end, a space or punctuation – never a letter or digit (snake_case stays as it is). */
private fun free(cp: Int?) = cp == null || !Character.isLetterOrDigit(cp)

fun formatted(text: String): Pair<String, List<FormatSpan>> {
    var chars = text.codePoints().toArray().map { Ch(it) }.toMutableList()
    fun joined() = buildString { chars.forEach { appendCodePoint(it.cp) } }
    // regex ranges are UTF-16 – here everything counts in code points like Python
    fun cpIndex(s: String, utf16: Int) = s.codePointCount(0, utf16)

    fun code(pattern: Regex) {
        val s = joined()
        val out = mutableListOf<Ch>()
        var at = 0
        for (m in pattern.findAll(s)) {
            val a = cpIndex(s, m.range.first); val b = cpIndex(s, m.range.last + 1)
            if ((a until b).any { "code" in chars[it].kinds }) continue
            val g = m.groups[1]!!.range
            out.addAll(chars.subList(at, a))
            for (k in cpIndex(s, g.first) until cpIndex(s, g.last + 1)) { chars[k].kinds.add("code"); out.add(chars[k]) }
            at = b
        }
        out.addAll(chars.subList(at, chars.size))
        chars = out
    }
    code(BLOCK)
    code(INLINE)

    // a written address stays as it is – https://x.de/_a_ is no italic (its signs belong to the link)
    joined().let { s -> for (link in findLinks(s)) for (k in cpIndex(s, link.range.first) until cpIndex(s, link.range.last + 1)) chars[k].kinds.add("roh") }

    // [Titel](https://…): the title shows, the address is the link
    run {
        val s = joined()
        val out = mutableListOf<Ch>()
        var at = 0
        for (m in TITLED.findAll(s)) {
            val a = cpIndex(s, m.range.first); val b = cpIndex(s, m.range.last + 1)
            if ((a until b).any { "code" in chars[it].kinds }) continue
            val g = m.groups[1]!!.range
            out.addAll(chars.subList(at, a))
            for (k in cpIndex(s, g.first) until cpIndex(s, g.last + 1)) { chars[k].kinds.remove("roh"); chars[k].target = m.groupValues[2]; out.add(chars[k]) }
            at = b
        }
        out.addAll(chars.subList(at, chars.size))
        chars = out
    }

    fun char(i: Int): Int? = chars.getOrNull(i)?.cp
    fun signAt(i: Int, sign: String) = sign.indices.all { k ->
        val c = chars.getOrNull(i + k); c != null && c.cp == sign[k].code && "code" !in c.kinds && "roh" !in c.kinds
    }
    fun space(cp: Int?) = cp != null && Character.isWhitespace(cp)

    for ((sign, kind) in PAIRS) {
        val n = sign.length
        val first = sign[0].code
        var i = 0
        while (i < chars.size) {
            val after = char(i + n)
            if (!signAt(i, sign) || !free(char(i - 1)) || char(i - 1) == first || after == null || space(after) || after == first) { i++; continue }
            var j = i + n
            var close: Int? = null
            while (j < chars.size && chars[j].cp != '\n'.code) {
                if (signAt(j, sign) && !space(char(j - 1)) && free(char(j + n)) && char(j + n) != first) { close = j; break }
                j++
            }
            if (close == null) { i++; continue }
            for (k in i + n until close) chars[k].kinds.add(kind)
            repeat(n) { chars.removeAt(close) }
            repeat(n) { chars.removeAt(i) }
            i = close - n
        }
    }

    // lines: „> “ a quote, „- “ / „* “ / „• “ a list point
    run {
        val out = mutableListOf<Ch>()
        var start = true
        var k = 0
        while (k < chars.size) {
            if (start && "code" !in chars[k].kinds) {
                val two = buildString { chars.subList(k, minOf(k + 2, chars.size)).forEach { appendCodePoint(it.cp) } }
                if (two == "> ") {
                    var m = k + 2
                    while (m < chars.size && chars[m].cp != '\n'.code) { chars[m].kinds.add("zitat"); m++ }
                    k += 2; start = false; continue
                }
                if (two == "- " || two == "* " || two == "• ") {
                    out.add(Ch('•'.code)); out.add(Ch(' '.code))
                    k += 2; start = false; continue
                }
            }
            out.add(chars[k])
            start = chars[k].cp == '\n'.code
            k++
        }
        chars = out
    }

    val plain = joined()
    // code point → UTF-16 offsets
    val offset = IntArray(chars.size + 1).also { o -> for (k in chars.indices) o[k + 1] = o[k] + Character.charCount(chars[k].cp) }
    val spans = mutableListOf<FormatSpan>()
    for (kind in listOf("fett", "kursiv", "unter", "durch", "code", "spoiler", "zitat", "link")) {
        var k = 0
        while (k < chars.size) {
            val has = if (kind == "link") chars[k].target != null else kind in chars[k].kinds
            if (!has) { k++; continue }
            val target = chars[k].target
            var end = k
            while (end < chars.size && (if (kind == "link") chars[end].target == target else kind in chars[end].kinds)) end++
            spans.add(FormatSpan(offset[k], offset[end], kind, if (kind == "link") target else null))
            k = end
        }
    }
    return plain to spans.sortedWith(compareBy({ it.start }, { it.end }, { it.kind }, { it.target ?: "" }))
}
