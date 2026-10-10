"""Rechnen im Textfeld (card bcb40e06, f028, like Math Notes in Messages): „87,60/4 =“ at the end of what is typed →
the result is offered („21,9“), a click puts it in. Only + − × ÷ : ^ %, brackets and numbers with comma or point – no
names, nothing is ever run as code. The twin of Kotlin's core/Calc.kt; both are checked against docs/rechnen-faelle.json."""

import re

TAIL = re.compile(r"(?<![\w.,])([-+(]*\d[\d,.]*(?:\s*[-+*/×÷:^%()]+\s*[\d,.()]*)*)\s*=\s*$")
LIMIT = 1e15


class _Parser:
    def __init__(self, text):
        self.tokens = re.findall(r"\d+(?:[.,]\d+)?|[-+*/×÷:^%()]", text.replace(" ", ""))
        if "".join(self.tokens) != text.replace(" ", ""):
            raise ValueError("unbekannte Zeichen")
        self.at = 0

    def peek(self):
        return self.tokens[self.at] if self.at < len(self.tokens) else None

    def take(self):
        self.at += 1
        return self.tokens[self.at - 1]

    def expression(self):
        value = self.term()
        while self.peek() in ("+", "-"):
            sign = self.take()
            right = self.term()
            if self.peek() == "%":              # 200+15% = 230, like a calculator
                self.take()
                right = value * right / 100
            value = value + right if sign == "+" else value - right
        return value

    def term(self):
        value = self.power()
        while self.peek() in ("*", "/", "×", "÷", ":"):
            sign = self.take()
            right = self.power()
            if sign in ("*", "×"):
                value *= right
            else:
                if right == 0:
                    raise ValueError("durch null")
                value /= right
        return value

    def power(self):
        base = self.unary()
        if self.peek() == "^":
            self.take()
            exponent = self.power()
            if abs(exponent) > 64:
                raise ValueError("zu groß")
            base = base ** exponent
        return base

    def unary(self):
        if self.peek() == "-":
            self.take()
            return -self.unary()
        if self.peek() == "+":
            self.take()
            return self.unary()
        return self.atom()

    def atom(self):
        token = self.peek()
        if token == "(":
            self.take()
            value = self.expression()
            if self.peek() != ")":
                raise ValueError("Klammer fehlt")
            self.take()
        elif token is not None and token[0].isdigit():
            value = float(self.take().replace(",", "."))
        else:
            raise ValueError("Zahl fehlt")
        if self.peek() == "%" and (self.at + 1 >= len(self.tokens) or self.tokens[self.at + 1] not in ("+", "-")):
            self.take()
            value /= 100
        return value


def german(value):
    """21.9 → „21,9“; at most ten places, no trailing zeros."""
    text = f"{value:.10f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text.replace(".", ",")


def suggestion(text):
    """The result for „… 87,60/4 =“ at the end of [text], or None (no sum, a phone number, an error)."""
    found = TAIL.search(text)
    if not found:
        return None
    sum_ = found.group(1)
    if not re.search(r"\d\s*[-+*/×÷:^%]\s*[\d(]|\d\s*%", sum_):   # an operator between numbers, not „um 12“
        return None
    if re.search(r"\d{4,}-\d{4,}", sum_):                         # 0171-1234567 is a phone number
        return None
    try:
        parser = _Parser(sum_)
        value = parser.expression()
        if parser.peek() is not None:
            return None
    except (ValueError, OverflowError, ZeroDivisionError):
        return None
    if value != value or abs(value) >= LIMIT:
        return None
    return german(round(value, 10))
