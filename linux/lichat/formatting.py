"""Text formatting in a message (card bcb40e06, f005–f011), the way it is typed on every keyboard, like WhatsApp:
*fett*  _kursiv_  __unterstrichen__  ~durchgestrichen~  `Code`  ```Block```  ||Spoiler||  [Titel](https://…)
and at the start of a line „> “ for a quote, „- “/„* “/„• “ for a list. The text travels as typed – an older LiChat shows
the signs, nothing breaks. The twin of Kotlin's core/Format.kt; both are checked against docs/formatierung-faelle.json.

formatted(text) → (plain, spans): the text as shown, and [(start, end, kind, target)] with kind in fett, kursiv, unter,
durch, code, spoiler, link (target = the address), zitat."""

import re

PAIRS = (("||", "spoiler"), ("__", "unter"), ("**", "fett"), ("*", "fett"), ("_", "kursiv"), ("~", "durch"))
TITLED = re.compile(r"\[([^\[\]\n]+)\]\((https?://[^\s()]+|mailto:[^\s()]+)\)")


def preview_text(text):
    """One line for the list and notifications: without the signs, a spoiler as „•••“ – it never shows there."""
    plain, spans = formatted(text)
    for start, end, _kind, _target in sorted((s for s in spans if s[2] == "spoiler"), reverse=True):
        plain = plain[:start] + "•••" + plain[end:]
    return " ".join(plain.split())          # one line – quotes and lists too (Michelle)


def _free(c):
    """Around a sign: the start or end, a space or punctuation – never a letter or digit (snake_case stays as it is)."""
    return c is None or not (c.isalnum())


def formatted(text):
    chars = [[c, set(), None] for c in text]          # character, kinds, link target

    def code(pattern, kind):
        # ```block``` and `code`: the signs go, nothing inside is formatted further
        nonlocal chars
        joined = "".join(c[0] for c in chars)
        out, at = [], 0
        for match in pattern.finditer(joined):
            if any("code" in chars[k][1] for k in range(match.start(), match.end())):
                continue
            out.extend(chars[at:match.start()])
            inner = match.start(1)
            for k in range(inner, match.end(1)):
                chars[k][1].add("code")
                out.append(chars[k])
            at = match.end()
        out.extend(chars[at:])
        chars = out
    code(re.compile(r"```\n?([\s\S]+?)\n?```"), "code")
    code(re.compile(r"`([^`\n]+)`"), "code")

    # a written address stays as it is – https://x.de/_a_ is no italic (its signs belong to the link)
    from .account import find_links
    for start, end, _target in find_links("".join(c[0] for c in chars)):
        for k in range(start, end):
            chars[k][1].add("roh")

    # [Titel](https://…): the title shows, the address is the link
    joined = "".join(c[0] for c in chars)
    out, at = [], 0
    for match in TITLED.finditer(joined):
        if any("code" in chars[k][1] for k in range(match.start(), match.end())):
            continue
        out.extend(chars[at:match.start()])
        for k in range(match.start(1), match.end(1)):
            chars[k][1].discard("roh")
            chars[k][2] = match.group(2)
            out.append(chars[k])
        at = match.end()
    out.extend(chars[at:])
    chars = out

    def sign_at(i, sign):
        return all(0 <= i + k < len(chars) and chars[i + k][0] == ch and not {"code", "roh"} & chars[i + k][1]
                   for k, ch in enumerate(sign))

    def char(i):
        return chars[i][0] if 0 <= i < len(chars) else None

    for sign, kind in PAIRS:
        n = len(sign)
        i = 0
        while i < len(chars):
            if not sign_at(i, sign) or not _free(char(i - 1)) or char(i - 1) == sign[0] \
                    or char(i + n) is None or char(i + n).isspace() or char(i + n) == sign[0]:
                i += 1
                continue
            j = i + n
            close = None
            while j < len(chars) and chars[j][0] != "\n":
                if sign_at(j, sign) and not char(j - 1).isspace() and _free(char(j + n)) and char(j + n) != sign[0]:
                    close = j
                    break
                j += 1
            if close is None:
                i += 1
                continue
            for k in range(i + n, close):
                chars[k][1].add(kind)
            del chars[close:close + n]
            del chars[i:i + n]
            i = close - n
    # lines: „> “ a quote, „- “ / „* “ / „• “ a list point
    out, start = [], True
    k = 0
    while k < len(chars):
        if start and "code" not in chars[k][1]:
            two = "".join(c[0] for c in chars[k:k + 2])
            if two == "> ":
                line_end = next((m for m in range(k + 2, len(chars)) if chars[m][0] == "\n"), len(chars))
                for m in range(k + 2, line_end):
                    chars[m][1].add("zitat")
                k += 2
                start = False
                continue
            if two in ("- ", "* ", "• "):
                out.append(["•", set(), None])
                out.append([" ", set(), None])
                k += 2
                start = False
                continue
        out.append(chars[k])
        start = chars[k][0] == "\n"
        k += 1
    chars = out

    plain = "".join(c[0] for c in chars)
    spans = []
    for kind in ("fett", "kursiv", "unter", "durch", "code", "spoiler", "zitat", "link"):
        k = 0
        while k < len(chars):
            has = (chars[k][2] is not None) if kind == "link" else kind in chars[k][1]
            if not has:
                k += 1
                continue
            end = k
            target = chars[k][2]
            while end < len(chars) and ((chars[end][2] == target) if kind == "link" else kind in chars[end][1]):
                end += 1
            spans.append((k, end, kind, target if kind == "link" else None))
            k = end
    return plain, sorted(spans)
