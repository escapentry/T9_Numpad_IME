package com.t9numpad.ime

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

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
    }

    fun loadDictionary(stream: InputStream) {
        val words = BufferedReader(InputStreamReader(stream)).useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()
        }
        val total = words.size
        words.forEachIndexed { index, word ->
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
