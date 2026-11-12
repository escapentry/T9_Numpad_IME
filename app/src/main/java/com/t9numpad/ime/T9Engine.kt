package com.t9numpad.ime

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.min

data class T9Candidate(
    val word: String,
    val score: Int,
    val isExact: Boolean,
    val isFuzzy: Boolean = false
)

class T9Engine {

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        val exactWords = ArrayList<Pair<String, Int>>()
    }

    private val root = TrieNode()
    private val orderedWords = ArrayList<String>()
    private val validWordSet = HashSet<String>()
    private val baseScores = HashMap<String, Int>()
    private val learnedFreqs = HashMap<String, Int>()
    private val learnedBigrams = HashMap<String, MutableMap<String, Int>>()

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

        // Spatial 3x4 keypad adjacency for Gboard/Swype-style slip tolerance
        private val KEYPAD_NEIGHBORS = mapOf(
            '2' to listOf('3', '5'),
            '3' to listOf('2', '6'),
            '4' to listOf('5', '7'),
            '5' to listOf('2', '4', '6', '8'),
            '6' to listOf('3', '5', '9'),
            '7' to listOf('4', '8'),
            '8' to listOf('5', '7', '9'),
            '9' to listOf('6', '8')
        )

        // Curated Gboard/Swype-style English bigram transitions
        private val COMMON_BIGRAMS = mapOf(
            "i" to listOf("am", "have", "will", "think", "was", "need", "want", "know", "can", "just", "love", "see"),
            "im" to listOf("on", "good", "sorry", "ready", "here", "going", "at", "not", "sure", "fine"),
            "you" to listOf("are", "can", "have", "know", "want", "need", "will", "should", "too", "ready"),
            "youre" to listOf("welcome", "right", "good", "the", "so"),
            "we" to listOf("are", "can", "have", "will", "need", "should", "could"),
            "they" to listOf("are", "were", "have", "will", "said"),
            "he" to listOf("is", "was", "said", "has", "will"),
            "she" to listOf("is", "was", "said", "has", "will"),
            "it" to listOf("is", "was", "will", "would", "looks", "sounds"),
            "its" to listOf("a", "the", "not", "so", "okay", "ok", "been", "good", "great"),
            "that" to listOf("is", "was", "would", "sounds", "looks"),
            "thats" to listOf("great", "good", "awesome", "fine", "true", "why"),
            "this" to listOf("is", "morning", "week", "one", "weekend", "afternoon"),
            "there" to listOf("is", "are", "was", "were"),
            "here" to listOf("is", "are", "we", "you"),
            "what" to listOf("is", "are", "do", "about", "time", "you"),
            "whats" to listOf("up", "new", "wrong", "your", "the"),
            "how" to listOf("are", "is", "much", "many", "about", "long", "do", "did", "was"),
            "where" to listOf("are", "is", "do", "did", "you"),
            "when" to listOf("are", "is", "do", "did", "you", "will"),
            "why" to listOf("not", "are", "is", "do", "did"),
            "who" to listOf("is", "are", "was", "did"),
            "are" to listOf("you", "we", "they", "there", "still"),
            "is" to listOf("it", "that", "this", "there", "on", "not", "good", "fine"),
            "was" to listOf("it", "that", "a", "the", "good", "great"),
            "am" to listOf("i", "on", "at", "going", "ready", "here"),
            "have" to listOf("a", "to", "you", "no", "some", "been", "fun"),
            "has" to listOf("been", "a", "the", "to"),
            "had" to listOf("a", "to", "no", "some"),
            "do" to listOf("you", "not", "it", "that", "this"),
            "did" to listOf("you", "it", "that", "not"),
            "dont" to listOf("know", "think", "worry", "forget", "want", "need", "mind"),
            "cant" to listOf("wait", "make", "find", "believe", "stop"),
            "wont" to listOf("be", "work", "let"),
            "will" to listOf("be", "you", "do", "have", "get", "see"),
            "would" to listOf("you", "be", "like", "love", "have"),
            "could" to listOf("you", "be", "have", "we"),
            "should" to listOf("be", "we", "i", "you"),
            "can" to listOf("you", "i", "we", "get", "be"),
            "on" to listOf("my", "the", "time", "your", "a", "this", "it"),
            "in" to listOf("the", "a", "my", "time", "front", "touch"),
            "at" to listOf("home", "work", "school", "the", "all", "least"),
            "to" to listOf("be", "the", "do", "go", "get", "see", "have", "make", "you", "me"),
            "for" to listOf("the", "you", "me", "a", "sure", "coffee", "lunch", "dinner"),
            "of" to listOf("the", "course", "a", "my", "this", "all"),
            "with" to listOf("you", "me", "the", "my", "us", "a"),
            "from" to listOf("the", "home", "work", "my", "you"),
            "by" to listOf("the", "now", "tomorrow", "tonight"),
            "about" to listOf("it", "the", "that", "you", "this", "five"),
            "my" to listOf("way", "home", "phone", "friend", "work", "bad", "car"),
            "your" to listOf("way", "home", "phone", "time", "day", "place"),
            "our" to listOf("way", "home", "place", "team"),
            "a" to listOf("good", "great", "lot", "little", "few", "new", "bit"),
            "the" to listOf("best", "same", "way", "first", "next", "other", "new", "time"),
            "no" to listOf("problem", "worries", "way", "idea", "one", "rush"),
            "not" to listOf("sure", "yet", "really", "now", "bad", "too"),
            "good" to listOf("morning", "night", "luck", "idea", "job", "evening", "afternoon", "to"),
            "great" to listOf("idea", "job", "day", "time", "news"),
            "happy" to listOf("birthday", "to", "hour", "weekend"),
            "heading" to listOf("home", "out", "to", "back", "over"),
            "coming" to listOf("home", "over", "back", "soon", "now"),
            "going" to listOf("home", "to", "out", "well"),
            "getting" to listOf("ready", "home", "late", "better"),
            "looking" to listOf("good", "for", "forward", "at"),
            "talking" to listOf("to", "about", "with"),
            "waiting" to listOf("for", "on", "at", "here"),
            "thank" to listOf("you", "god", "goodness"),
            "thanks" to listOf("for", "so", "a", "again"),
            "please" to listOf("let", "send", "call", "check", "wait"),
            "sorry" to listOf("i", "for", "about", "to", "late"),
            "see" to listOf("you", "ya", "the", "if", "what"),
            "talk" to listOf("to", "soon", "later", "about"),
            "call" to listOf("me", "you", "back", "later"),
            "text" to listOf("me", "you", "back", "later"),
            "send" to listOf("me", "it", "a", "the"),
            "tell" to listOf("me", "him", "her", "them", "us"),
            "give" to listOf("me", "you", "it", "a"),
            "let" to listOf("me", "us", "know", "it"),
            "lets" to listOf("go", "meet", "do", "talk", "get"),
            "make" to listOf("it", "sure", "a", "sense"),
            "take" to listOf("care", "a", "it", "the"),
            "sounds" to listOf("good", "great", "like", "fun"),
            "looks" to listOf("good", "great", "like"),
            "one" to listOf("of", "more", "thing", "minute", "second"),
            "all" to listOf("good", "day", "night", "right", "the"),
            "right" to listOf("now", "here", "back", "away"),
            "so" to listOf("good", "much", "far", "soon", "sorry"),
            "too" to listOf("bad", "much", "late", "soon"),
            "very" to listOf("good", "much", "nice", "soon"),
            "hey" to listOf("there", "how", "you", "man", "everyone"),
            "hi" to listOf("there", "everyone", "how"),
            "hello" to listOf("there", "everyone", "world"),
            "yes" to listOf("i", "it", "please", "of", "sure"),
            "yeah" to listOf("i", "sure", "it", "sounds", "im"),
            "okay" to listOf("sounds", "cool", "great", "see", "i"),
            "ok" to listOf("sounds", "cool", "great", "see", "i")
        )

        private val DEFAULT_STARTERS = listOf("hey", "yes", "i", "thanks", "good", "okay")

        private val COMMON_TYPOS = mapOf(
            "teh" to "the", "hte" to "the", "adn" to "and", "nad" to "and",
            "taht" to "that", "waht" to "what", "hwat" to "what", "wiht" to "with",
            "thsi" to "this", "jsut" to "just", "liek" to "like", "fomr" to "from",
            "yuo" to "you", "helo" to "hello", "hllo" to "hello", "gdod" to "good",
            "godo" to "good", "hmoe" to "home", "im" to "I'm", "ive" to "I've",
            "ill" to "I'll", "dont" to "don't", "cant" to "can't", "wont" to "won't",
            "didnt" to "didn't", "isnt" to "isn't", "arent" to "aren't",
            "youre" to "you're", "theyre" to "they're", "thats" to "that's",
            "whats" to "what's", "lets" to "let's", "theres" to "there's",
            "heres" to "here's", "becuase" to "because", "beacuse" to "because",
            "recieve" to "receive", "thier" to "their", "definately" to "definitely",
            "seperate" to "separate", "tomorow" to "tomorrow", "tommorow" to "tomorrow",
            "probaly" to "probably", "realy" to "really", "untill" to "until",
            "alot" to "a lot", "occured" to "occurred", "wierd" to "weird",
            "freind" to "friend", "beleive" to "believe", "bussiness" to "business",
            "goverment" to "government", "langauge" to "language",
            "acheive" to "achieve", "adress" to "address"
        )
    }

    fun loadDictionary(stream: InputStream) {
        val words = BufferedReader(InputStreamReader(stream)).useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()
        }
        val total = words.size
        orderedWords.clear()
        validWordSet.clear()
        baseScores.clear()
        words.forEachIndexed { index, word ->
            if (validWordSet.add(word)) {
                orderedWords.add(word)
            }
            val score = (total - index) + 200
            baseScores[word] = score
            insertWord(word, score + (learnedFreqs[word] ?: 0) * 450)
        }
    }

    fun applyLearnedFrequencies(freqMap: Map<String, Int>) {
        learnedFreqs.clear()
        freqMap.forEach { (raw, count) ->
            val clean = raw.trim().lowercase()
            if (clean.length >= 2 && count > 0) {
                learnedFreqs[clean] = count
                val base = baseScores[clean] ?: 4500
                insertWord(clean, base + count * 450)
            }
        }
    }

    fun applyLearnedBigrams(bigrams: Map<String, Map<String, Int>>) {
        learnedBigrams.clear()
        bigrams.forEach { (rawPrev, nextMap) ->
            val prev = rawPrev.trim().lowercase()
            if (prev.isNotEmpty()) {
                val dest = HashMap<String, Int>()
                nextMap.forEach { (rawNext, count) ->
                    val next = rawNext.trim().lowercase()
                    if (next.isNotEmpty() && count > 0) dest[next] = count
                }
                if (dest.isNotEmpty()) learnedBigrams[prev] = dest
            }
        }
    }

    fun recordWordUsage(rawWord: String, prevWord: String = ""): Int {
        val clean = rawWord.trim().lowercase()
        if (clean.length < 2) return 0
        val nextCount = (learnedFreqs[clean] ?: 0) + 1
        learnedFreqs[clean] = nextCount
        val base = baseScores[clean] ?: 4500
        insertWord(clean, base + nextCount * 450)

        val cleanPrev = prevWord.trim().lowercase()
        if (cleanPrev.isNotEmpty() && cleanPrev != clean) {
            val map = learnedBigrams.getOrPut(cleanPrev) { HashMap() }
            map[clean] = (map[clean] ?: 0) + 1
        }
        return nextCount
    }

    fun hasWord(word: String): Boolean {
        val clean = word.trim().lowercase()
        return clean.length >= 2 && validWordSet.contains(clean)
    }

    fun insertWord(word: String, score: Int) {
        val clean = word.trim().lowercase()
        val digits = wordToDigits(clean) ?: return
        if (validWordSet.add(clean)) {
            orderedWords.add(0, clean)
        }
        if (!baseScores.containsKey(clean) && score >= 10000) {
            baseScores[clean] = score
        }
        var node = root
        for (d in digits) {
            node = node.children.getOrPut(d) { TrieNode() }
        }
        val idx = node.exactWords.indexOfFirst { it.first == clean }
        if (idx >= 0) {
            if (score > node.exactWords[idx].second) {
                node.exactWords[idx] = clean to score
            }
        } else {
            node.exactWords.add(clean to score)
        }
        node.exactWords.sortByDescending { it.second }
    }

    private fun findNode(digits: String): TrieNode? {
        var node: TrieNode? = root
        for (d in digits) {
            node = node?.children?.get(d) ?: return null
        }
        return node
    }

    private fun getContextBonus(word: String, prevWord: String): Int {
        if (prevWord.isEmpty()) return 0
        val prev = prevWord.trim().lowercase()
        if (prev.isEmpty()) return 0
        var bonus = 0
        val learnedCount = learnedBigrams[prev]?.get(word) ?: 0
        if (learnedCount > 0) {
            bonus += 2400 + min(learnedCount, 10) * 650
        }
        val staticNext = COMMON_BIGRAMS[prev]
        if (staticNext != null) {
            val rank = staticNext.indexOf(word)
            if (rank >= 0) {
                bonus += 3400 - rank * 220
            }
        }
        return bonus
    }

    // Zero-digit Gboard/Swype Next-Word Prediction
    fun predictNextWords(prevWord: String = "", limit: Int = 5): List<String> {
        val prev = prevWord.trim().lowercase()
        if (prev.isEmpty()) return DEFAULT_STARTERS.take(limit)

        val scored = HashMap<String, Int>()
        learnedBigrams[prev]?.forEach { (nextW, count) ->
            if (count > 0 && nextW.isNotEmpty()) {
                scored[nextW] = (scored[nextW] ?: 0) + 5000 + count * 800
            }
        }
        COMMON_BIGRAMS[prev]?.forEachIndexed { idx, nextW ->
            scored[nextW] = (scored[nextW] ?: 0) + 3200 - idx * 200
        }
        if (scored.isEmpty()) return emptyList()
        return scored.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    fun predict(digits: String, limit: Int = 8, prevWord: String = ""): List<T9Candidate> {
        if (digits.isEmpty()) return emptyList()
        val node = findNode(digits)
        val results = ArrayList<T9Candidate>()
        val seen = HashSet<String>()

        if (node != null) {
            val exactScored = node.exactWords
                .map { (w, s) -> T9Candidate(w, s + getContextBonus(w, prevWord), isExact = true) }
                .sortedByDescending { it.score }

            for (c in exactScored) {
                if (seen.add(c.word)) results.add(c)
            }

            val completions = ArrayList<Pair<String, Int>>()
            collectCompletions(node, depth = 0, out = completions)
            val compScored = completions
                .map { (w, s) -> w to (s + getContextBonus(w, prevWord)) }
                .sortedByDescending { it.second }

            for ((word, score) in compScored) {
                if (results.size >= limit) break
                if (seen.add(word)) {
                    results.add(T9Candidate(word, score, isExact = false))
                }
            }
        }

        // Spatial Fuzzy T9 Recovery (Swype/Gboard neighbor slip & transposition recovery)
        if (results.none { it.isExact } && digits.length >= 2) {
            val fuzzyVariants = ArrayList<Pair<String, Int>>()
            for (i in digits.indices) {
                val neighbors = KEYPAD_NEIGHBORS[digits[i]] ?: emptyList()
                for (nb in neighbors) {
                    fuzzyVariants.add((digits.substring(0, i) + nb + digits.substring(i + 1)) to 1800)
                }
            }
            for (i in 0 until digits.length - 1) {
                if (digits[i] != digits[i + 1]) {
                    val swapped = digits.substring(0, i) + digits[i + 1] + digits[i] + digits.substring(i + 2)
                    fuzzyVariants.add(swapped to 1500)
                }
            }
            if (digits.length >= 3) {
                for (i in digits.indices) {
                    fuzzyVariants.add((digits.substring(0, i) + digits.substring(i + 1)) to 2200)
                }
            }

            val fuzzyList = ArrayList<T9Candidate>()
            for ((seq, penalty) in fuzzyVariants) {
                val vNode = findNode(seq) ?: continue
                for ((w, s) in vNode.exactWords) {
                    val ctxScore = (s + getContextBonus(w, prevWord) - penalty).coerceAtLeast(1)
                    fuzzyList.add(T9Candidate(w, ctxScore, isExact = false, isFuzzy = true))
                }
            }
            fuzzyList.sortByDescending { it.score }
            for (fc in fuzzyList) {
                if (results.size >= limit) break
                if (seen.add(fc.word)) results.add(fc)
            }
        }

        if (results.none { it.isExact || it.isFuzzy }) {
            val fallback = digits.map { d ->
                MULTITAP_MAP[d]?.firstOrNull() ?: d
            }.joinToString("")
            if (fallback.isNotEmpty() && seen.add(fallback)) {
                results.add(T9Candidate(fallback, 0, isExact = true))
            }
        }

        return results.take(limit)
    }

    // Weighted noisy-channel Auto-Correct — runs ONLY when T9 is OFF (ABC multi-tap mode)
    fun autoCorrect(rawWord: String, prevWord: String = ""): String? {
        if (rawWord.length < 2) return null
        val lower = rawWord.lowercase()
        COMMON_TYPOS[lower]?.let { return matchCase(rawWord, it) }
        if (validWordSet.contains(lower)) return null
        if ((learnedFreqs[lower] ?: 0) >= 2) return null

        val prev = prevWord.trim().lowercase()
        val staticNext = if (prev.isNotEmpty()) COMMON_BIGRAMS[prev] ?: emptyList() else emptyList()
        val learnedNext = if (prev.isNotEmpty()) learnedBigrams[prev] ?: emptyMap() else emptyMap()

        var bestCand: String? = null
        var bestScore = Int.MIN_VALUE

        val limit = min(orderedWords.size, 3500)
        for (idx in 0 until limit) {
            val cand = orderedWords[idx]
            if (abs(cand.length - lower.length) > 2) continue
            val cost = weightedEditCost(lower, cand) ?: continue
            val prior = (baseScores[cand] ?: (3600 - idx)).coerceAtLeast(50)
            val freqBonus = (learnedFreqs[cand] ?: 0) * 500
            var ctxBonus = 0
            if (prev.isNotEmpty()) {
                val lb = learnedNext[cand] ?: 0
                if (lb > 0) ctxBonus += 2400 + min(lb, 8) * 500
                val sIdx = staticNext.indexOf(cand)
                if (sIdx >= 0) ctxBonus += 3000 - sIdx * 200
            }
            val total = prior + freqBonus + ctxBonus - (cost * 2600.0).toInt()
            if (total > bestScore) {
                bestScore = total
                bestCand = cand
            }
        }

        if (bestCand == null || bestCand == lower || bestScore < 400) return null
        return matchCase(rawWord, bestCand)
    }

    private fun weightedEditCost(typed: String, candidate: String): Double? {
        val la = typed.length
        val lb = candidate.length
        if (abs(la - lb) > 2) return null

        if (la == lb) {
            val diffs = IntArray(3)
            var diffCount = 0
            for (i in 0 until la) {
                if (typed[i] != candidate[i]) {
                    if (diffCount >= 2) return null
                    diffs[diffCount++] = i
                }
            }
            if (diffCount == 0) return 0.0
            if (diffCount == 1) {
                val idx = diffs[0]
                val d1 = CHAR_TO_DIGIT[typed[idx]]
                val d2 = CHAR_TO_DIGIT[candidate[idx]]
                if (d1 != null && d1 == d2) return 0.35
                if (d1 != null && d2 != null && KEYPAD_NEIGHBORS[d1]?.contains(d2) == true) return 0.65
                return 1.0
            }
            val i0 = diffs[0]
            val i1 = diffs[1]
            if (i1 == i0 + 1 && typed[i0] == candidate[i1] && typed[i1] == candidate[i0]) {
                return 0.45
            }
            if (la < 5) return null
            val c0 = if (CHAR_TO_DIGIT[typed[i0]] == CHAR_TO_DIGIT[candidate[i0]]) 0.4 else 1.0
            val c1 = if (CHAR_TO_DIGIT[typed[i1]] == CHAR_TO_DIGIT[candidate[i1]]) 0.4 else 1.0
            val sum = c0 + c1
            return if (sum <= 1.55) sum else null
        }

        if (abs(la - lb) == 1) {
            val shorter = if (la < lb) typed else candidate
            val longer = if (la < lb) candidate else typed
            var i = 0
            var j = 0
            var skipped = false
            var skippedChar = longer.last()
            while (i < shorter.length && j < longer.length) {
                if (shorter[i] == longer[j]) {
                    i++; j++
                } else {
                    if (skipped) return null
                    skipped = true
                    skippedChar = longer[j]
                    j++
                }
            }
            val repeated = longer.contains("" + skippedChar + skippedChar)
            return if (repeated) 0.48 else 0.95
        }

        if (la >= 6 && lb >= 6 && abs(la - lb) == 2) {
            val dp = Array(la + 1) { IntArray(lb + 1) }
            for (i in 0..la) dp[i][0] = i
            for (j in 0..lb) dp[0][j] = j
            for (i in 1..la) {
                for (j in 1..lb) {
                    val cost = if (typed[i - 1] == candidate[j - 1]) 0 else 1
                    var best = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && typed[i - 1] == candidate[j - 2] && typed[i - 2] == candidate[j - 1]) {
                        best = min(best, dp[i - 2][j - 2] + 1)
                    }
                    dp[i][j] = best
                }
            }
            return if (dp[la][lb] <= 2) 1.85 else null
        }

        return null
    }

    private fun matchCase(original: String, corrected: String): String {
        if (original == original.uppercase() && original.length > 1) return corrected.uppercase()
        if (original.first().isUpperCase()) {
            return corrected.replaceFirstChar { it.uppercase() }
        }
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
