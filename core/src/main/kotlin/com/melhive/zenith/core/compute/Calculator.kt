package com.melhive.zenith.core.compute

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Deterministic arithmetic evaluator (recursive descent). Supports + - * / ^ %,
 * parentheses, unary minus, sqrt/abs/round/ln/log/sin/cos/tan, pi and e, and
 * spoken operators ("plus", "times", "divided by", "squared", "15% of 80").
 */
object Calculator {
    class CalcException(message: String) : Exception(message)

    /** Returns null when the text is not a calculation. */
    fun tryEvaluate(text: String): Double? {
        val expr = normalize(text) ?: return null
        return try { evaluate(expr) } catch (e: CalcException) { null }
    }

    private val LEAD = Regex("^(?:what(?:'s| is)|how much is|calculate|compute|evaluate|solve|work out)\\s+(?:the\\s+)?", RegexOption.IGNORE_CASE)

    /** Converts natural phrasing to a symbolic expression, or null if it doesn't look like math. */
    fun normalize(text: String): String? {
        var t = text.trim().trimEnd('?', '.', '!').trim()
        t = LEAD.replace(t, "")
        t = t.lowercase()
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+of\\s+"), "($1/100)*")
            .replace(Regex("\\bsquare root of\\b"), "sqrt ")
            .replace(Regex("\\bto the power of\\b"), "^")
            .replace(Regex("\\bsquared\\b"), "^2")
            .replace(Regex("\\bcubed\\b"), "^3")
            .replace(Regex("\\bmultiplied by\\b|\\btimes\\b"), "*")
            .replace(Regex("\\bdivided by\\b|\\bover\\b"), "/")
            .replace(Regex("\\bplus\\b"), "+")
            .replace(Regex("\\bminus\\b"), "-")
            .replace(Regex("\\bmod(?:ulo)?\\b"), "%")
            .replace(Regex("(?<=\\d)\\s*[x×]\\s*(?=\\d|\\()"), "*")
            .replace('÷', '/')
            .replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")
            .trim()
        if (t.isEmpty()) return null
        if (!t.any { it.isDigit() } && !t.contains("pi")) return null
        if (!Regex("^[0-9a-z+\\-*/^%().\\s]+$").matches(t)) return null
        // Only known words allowed.
        val words = Regex("[a-z]+").findAll(t).map { it.value }.toSet()
        if (!FUNCTIONS.keys.containsAll(words - setOf("pi", "e"))) return null
        // Must contain an operator or a function to count as a calculation.
        if (!Regex("[+\\-*/^%]").containsMatchIn(t.replace(Regex("^-"), "")) && words.none { it in FUNCTIONS }) return null
        return t
    }

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "sqrt" to { x -> if (x < 0) throw CalcException("square root of a negative number") else sqrt(x) },
        "abs" to { x -> abs(x) },
        "round" to { x -> kotlin.math.round(x) },
        "ln" to { x -> if (x <= 0) throw CalcException("ln of non-positive number") else ln(x) },
        "log" to { x -> if (x <= 0) throw CalcException("log of non-positive number") else log10(x) },
        "sin" to { x -> kotlin.math.sin(Math.toRadians(x)) },
        "cos" to { x -> kotlin.math.cos(Math.toRadians(x)) },
        "tan" to { x -> kotlin.math.tan(Math.toRadians(x)) },
    )

    fun evaluate(expr: String): Double {
        val p = Parser(expr.replace(" ", ""))
        val v = p.parseExpression()
        if (!p.atEnd()) throw CalcException("unexpected '${p.rest()}'")
        if (v.isNaN() || v.isInfinite()) throw CalcException("result is undefined")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun rest() = s.substring(i)
        private fun peek() = if (i < s.length) s[i] else '\u0000'

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) v = when (peek()) {
                '+' -> { i++; v + parseTerm() }
                '-' -> { i++; v - parseTerm() }
                else -> return v
            }
        }

        fun parseTerm(): Double {
            var v = parseFactor()
            while (true) v = when (peek()) {
                '*' -> { i++; v * parseFactor() }
                '/' -> { i++; val d = parseFactor(); if (d == 0.0) throw CalcException("division by zero"); v / d }
                '%' -> { i++; val d = parseFactor(); if (d == 0.0) throw CalcException("division by zero"); v % d }
                '(' -> v * parseFactor() // implicit multiplication: 2(3+4)
                else -> return v
            }
        }

        fun parseFactor(): Double {
            val base = parseUnary()
            if (peek() == '^') { i++; return base.pow(parseFactor()) } // right-associative
            return base
        }

        fun parseUnary(): Double = when (peek()) {
            '-' -> { i++; -parseUnary() }
            '+' -> { i++; parseUnary() }
            else -> parsePrimary()
        }

        fun parsePrimary(): Double {
            val c = peek()
            if (c == '(') {
                i++
                val v = parseExpression()
                if (peek() != ')') throw CalcException("missing ')'")
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (peek().isDigit() || peek() == '.') i++
                return s.substring(start, i).toDoubleOrNull() ?: throw CalcException("bad number")
            }
            if (c.isLetter()) {
                val start = i
                while (peek().isLetter()) i++
                val name = s.substring(start, i)
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> {
                        val f = FUNCTIONS[name] ?: throw CalcException("unknown function $name")
                        f(parseFactor())
                    }
                }
            }
            throw CalcException(if (atEnd()) "incomplete expression" else "unexpected '${c}'")
        }
    }

    /** The user's expression without lead-in words, for echoing back. */
    fun display(text: String): String = LEAD.replace(text.trim().trimEnd('?', '.', '!').trim(), "").trim()

    /** Formats without floating-point noise: 0.1+0.2 → 0.3, 1e21 stays readable. */
    fun format(v: Double): String {
        if (v == kotlin.math.floor(v) && abs(v) < 1e15) return v.toLong().toString()
        val bd = BigDecimal(v).round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        return if (abs(v) >= 1e15 || (abs(v) < 1e-6 && v != 0.0)) bd.toString() else bd.toPlainString()
    }
}
