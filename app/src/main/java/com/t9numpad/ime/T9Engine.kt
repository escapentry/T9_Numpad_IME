package com.t9numpad.ime

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs

data class T9Candidate(
    val word: String,
    val score: Int,
    val isExact: Boolean
)

class T9Engine {

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        val exactWords = ArrayList<Pair<String, Int>>()
    }

    private val root = TrieNode()
    private val orderedWords = ArrayList<String>()
    private val validWordSet = HashSet<String>()

    companion object {
        val MULTITAP_MAP = mapOf(
            '1' to listOf('.', ',', '?', '!', '\'', '-', ':', '/', '@', '1'),
            '2' to listOf('a', 'b', 'c', '2'),
            '3' to listOf('d', 'e', 'f', '3'),
            '4' to listOf('g', 'h', 'i', '4'),
            '5' to listOf('j', 'k', 'l', '5'),
            '6' to listOf('m', 'n', 'o', '6'),
            '7' to listOf('p', 'q', 'r', 's', '7'),
            '8' to listOf('t', 'u', 'v', '8'),
            '9' to listOf('w', 'x', 'y', 'z', '9')
        )

        private val CHAR_TO_DIGIT = mapOf(
            'a' to '2', 'b' to '2', 'c' to '2',
            'd' to '3', 'e' to '3', 'f' to '3',
            'g' to '4', 'h' to '4', 'i' to '4',
            'j' to '5', 'k' to '5', 'l' to '5',
            'm' to '6', 'n' to '6', 'o' to '6',
            'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
            't' to '8', 'u' to '8', 'v' to '8',
            'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
        )

        private val COMMON_TYPOS = mapOf(
            "teh" to "the", "adn" to "and", "taht" to "that", "waht" to "what",
            "wiht" to "with", "thsi" to "this", "jsut" to "just", "liek" to "like",
            "helo" to "hello", "gdod" to "good", "hmoe" to "home",
            "im" to "I'm", "ive" to "I've", "ill" to "I'll",
            "dont" to "don't", "cant" to "can't", "wont" to "won't",
            "didnt" to "didn't", "isnt" to "isn't", "youre" to "you're",
            "theyre" to "they're", "thats" to "that's", "whats" to "what's", "lets" to "let's"
        )
    }

    fun loadDictionary(stream: InputStream) {
        val words = BufferedReader(InputStreamReader(stream)).useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()
        }
        val total = words.size
        orderedWords.clear()
        validWordSet.clear()
        words.forEachIndexed { index, word ->
            if (validWordSet.add(word)) {
                orderedWords.add(word)
            }
            insertWord(word, (total - index) + 100)
        }
    }

    fun insertWord(word: String, score: Int) {
        val digits = wordToDigits(word) ?: return
        var node = root
        for (d in digits) {
            node = node.children.getOrPut(d) { TrieNode() }
        }
        node.exactWords.add(word to score)
        node.exactWords.sortByDescending { it.second }
    }

    fun predict(digits: String, limit: Int = 8): List<T9Candidate> {
        if (digits.isEmpty()) return emptyList()
        var node: TrieNode? = root
        for (d in digits) {
            node = node?.children?.get(d)
            if (node == null) break
        }

        val results = ArrayList<T9Candidate>()
        val seen = HashSet<String>()

        if (node != null) {
            for ((word, score) in node.exactWords) {
                if (seen.add(word)) {
                    results.add(T9Candidate(word, score, isExact = true))
                }
            }
            val completions = ArrayList<Pair<String, Int>>()
            collectCompletions(node, depth = 0, out = completions)
            completions.sortByDescending { it.second }
            for ((word, score) in completions) {
                if (results.size >= limit) break
                if (seen.add(word)) {
                    results.add(T9Candidate(word, score, isExact = false))
                }
            }
        }

        if (results.none { it.isExact }) {
            val fallback = digits.map { d ->
                MULTITAP_MAP[d]?.firstOrNull() ?: d
            }.joinToString("")
            results.add(T9Candidate(fallback, 0, isExact = true))
        }

        return results.take(limit)
    }

    // Used only when T9 is OFF (ABC multi-tap mode)
    fun autoCorrect(rawWord: String): String? {
        if (rawWord.length < 2) return null
        val lower = rawWord.lowercase()
        COMMON_TYPOS[lower]?.let { return matchCase(rawWord, it) }
        if (validWordSet.contains(lower)) return null

        val inputDigits = wordToDigits(lower)
        var bestSameKey: String? = null
        var bestAny: String? = null

        for (candidate in orderedWords) {
            if (isDamerauEditDistance1(lower, candidate)) {
                if (inputDigits != null && wordToDigits(candidate) == inputDigits) {
                    bestSameKey = candidate
                    break
                }
                if (bestAny == null) bestAny = candidate
            }
        }

        val chosen = bestSameKey ?: bestAny ?: return null
        return matchCase(rawWord, chosen)
    }

    private fun isDamerauEditDistance1(a: String, b: String): Boolean {
        val la = a.length
        val lb = b.length
        if (abs(la - lb) > 1) return false
        if (la == lb) {
            var diffs = 0
            var first = -1
            var second = -1
            for (i in 0 until la) {
                if (a[i] != b[i]) {
                    diffs++
                    if (first == -1) first = i else if (second == -1) second = i else return false
                }
            }
            if (diffs == 1) return true
            if (diffs == 2 && second == first + 1) {
                return a[first] == b[second] && a[second] == b[first]
            }
            return false
        }
        val shorter = if (la < lb) a else b
        val longer = if (la < lb) b else a
        var i = 0
        var j = 0
        var skipped = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++; j++
            } else {
                if (skipped) return false
                skipped = true; j++
            }
        }
        return true
    }

    private fun matchCase(original: String, corrected: String): String {
        if (original == original.uppercase() && original.length > 1) return corrected.uppercase()
        if (original.first().isUpperCase()) return corrected.replaceFirstChar { it.uppercase() }
        return corrected
    }

    private fun collectCompletions(node: TrieNode, depth: Int, out: MutableList<Pair<String, Int>>) {
        if (depth > 4) return
        for (child in node.children.values) {
            out.addAll(child.exactWords)
            collectCompletions(child, depth + 1, out)
        }
    }

    private fun wordToDigits(word: String): String? {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            val d = CHAR_TO_DIGIT[ch] ?: return null
            sb.append(d)
        }
        return sb.toString()
    }
}
