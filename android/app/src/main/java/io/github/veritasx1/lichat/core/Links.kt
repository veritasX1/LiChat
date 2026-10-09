package io.github.veritasx1.lichat.core

/** Links in a message like Messages (Olaf 22c918ce): web addresses, mail addresses and phone numbers become tappable.
 *  Only recognised here, on the phone – nothing is fetched for a preview (privacy: no request leaves without a tap). */
data class Link(val range: IntRange, val target: String)

private val WEB = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
private val MAIL = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")
private val PHONE = Regex("""(?<![\w/])\+?\d[\d \-/]{5,}\d(?![\w/])""")

fun findLinks(text: String): List<Link> {
    val found = mutableListOf<Link>()
    fun free(r: IntRange) = found.none { it.range.first <= r.last && r.first <= it.range.last }
    for (m in WEB.findAll(text)) {
        val value = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '»', '“')
        val range = m.range.first until m.range.first + value.length
        found.add(Link(range, if (value.lowercase().startsWith("www.")) "https://$value" else value))
    }
    for (m in MAIL.findAll(text)) if (free(m.range)) found.add(Link(m.range, "mailto:${m.value}"))
    for (m in PHONE.findAll(text)) {
        val digits = m.value.count { it.isDigit() }
        if (digits in 7..15 && free(m.range)) found.add(Link(m.range, "tel:" + m.value.filter { it.isDigit() || it == '+' }))
    }
    return found.sortedBy { it.range.first }
}
