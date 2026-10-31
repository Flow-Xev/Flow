package app.zaid.flow.data.translate

private val WORD = Regex("[\\p{L}']+")
private const val ENGLISH_RATIO = 0.3
private const val NON_LATIN_RATIO = 0.3

private val ENGLISH_WORDS =
    setOf(
        "the",
        "a",
        "an",
        "and",
        "or",
        "but",
        "is",
        "are",
        "was",
        "were",
        "be",
        "been",
        "to",
        "of",
        "in",
        "on",
        "at",
        "for",
        "with",
        "this",
        "that",
        "it",
        "i",
        "you",
        "he",
        "she",
        "we",
        "they",
        "my",
        "your",
        "not",
        "no",
        "so",
        "very",
        "just",
        "have",
        "has",
        "had",
        "do",
        "does",
        "did",
        "can",
        "will",
        "would",
        "what",
        "who",
        "how",
        "why",
        "when",
        "from",
        "as",
        "if",
        "all",
        "one",
        "more",
        "like",
        "love",
        "great",
        "good",
        "thanks",
        "thank",
        "me",
        "up",
        "out",
        "about",
        "there",
        "here",
        "really",
        "best",
        "nice",
        "got",
        "get",
        "much",
        "too",
        "im",
        "its",
        "dont",
    )

/** Romanised Hindi (Hinglish) words that are not also common English words. */
private val HINGLISH_WORDS =
    setOf(
        "ye",
        "yeh",
        "yah",
        "ka",
        "ki",
        "ke",
        "ko",
        "hai",
        "hain",
        "hoon",
        "tha",
        "thi",
        "bahut",
        "bohot",
        "bahot",
        "bohat",
        "accha",
        "acha",
        "achha",
        "achhi",
        "achchha",
        "achi",
        "bhai",
        "bhaiya",
        "nahi",
        "nhi",
        "nahin",
        "kya",
        "kyu",
        "kyun",
        "kaise",
        "kitna",
        "kitni",
        "mera",
        "meri",
        "mere",
        "tum",
        "tumhara",
        "tumhari",
        "aap",
        "aapka",
        "apka",
        "bhi",
        "aur",
        "lekin",
        "mein",
        "wala",
        "wali",
        "sabse",
        "bilkul",
        "zabardast",
        "mast",
        "shandar",
        "jaldi",
        "abhi",
        "karo",
        "kiya",
        "kiye",
        "dekh",
        "dekho",
        "sach",
        "sahi",
        "galat",
        "dost",
        "yaar",
        "yar",
        "ekdum",
        "bohut",
        "lajawab",
        "badhiya",
        "kamaal",
        "kaam",
        "pasand",
        "gaana",
        "gana",
        "wah",
        "waah",
        "dil",
        "pyaar",
        "bahot",
    )

/** Chat-speak, texting spellings and typos mapped to the proper English word. */
private val INFORMAL_ENGLISH =
    mapOf(
        "gud" to "good",
        "gd" to "good",
        "vdo" to "video",
        "vedio" to "video",
        "vid" to "video",
        "osm" to "awesome",
        "awsm" to "awesome",
        "awsome" to "awesome",
        "nyc" to "nice",
        "plz" to "please",
        "pls" to "please",
        "plzz" to "please",
        "u" to "you",
        "ur" to "your",
        "r" to "are",
        "y" to "why",
        "vry" to "very",
        "wrk" to "work",
        "thx" to "thanks",
        "thnx" to "thanks",
        "tnx" to "thanks",
        "luv" to "love",
        "lv" to "love",
        "gr8" to "great",
        "b4" to "before",
        "bcz" to "because",
        "bcoz" to "because",
        "cuz" to "because",
        "coz" to "because",
        "wat" to "what",
        "wht" to "what",
        "dis" to "this",
        "dat" to "that",
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "im" to "I'm",
        "ive" to "I've",
        "idk" to "I don't know",
        "tbh" to "to be honest",
        "ngl" to "not gonna lie",
        "btw" to "by the way",
        "gonna" to "going to",
        "wanna" to "want to",
        "gotta" to "got to",
        "nic" to "nice",
        "grt" to "great",
        "amzing" to "amazing",
        "amazin" to "amazing",
        "bst" to "best",
        "fav" to "favorite",
        "lyk" to "like",
        "lik" to "like",
        "sooo" to "so",
        "soooo" to "so",
        "veryy" to "very",
        "vdeo" to "video",
        "viedo" to "video",
        "wrking" to "working",
        "thankyou" to "thank you",
        "thanku" to "thank you",
        "frnd" to "friend",
        "frnds" to "friends",
        "knw" to "know",
        "kno" to "know",
    )

/** True when [text] is mostly romanised Hindi ("ye video bahut achhi hai", "bhai kya mast video hai"). */
internal fun isRomanizedHindi(text: String): Boolean {
    val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
    if (words.isEmpty()) return false
    val hits = words.count { it in HINGLISH_WORDS }
    return hits >= 2 || (hits >= 1 && hits.toDouble() / words.size >= 0.3)
}

/** True when [text] contains chat-speak or misspelt English such as "gud vdo bro" or "u r osm". */
internal fun isInformalEnglish(text: String): Boolean {
    val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
    if (words.size < 2) return false
    return words.any { it in INFORMAL_ENGLISH }
}

/** Rewrites chat-speak and common misspellings into proper English; the rest of the text is left alone. */
internal fun normalizeInformalEnglish(text: String): String {
    val fixed =
        WORD.replace(text) { m ->
            val word = m.value
            val replacement = INFORMAL_ENGLISH[word.lowercase()] ?: return@replace word
            if (word.first().isUpperCase() || replacement.first().isUpperCase()) {
                replacement.replaceFirstChar { it.uppercase() }
            } else {
                replacement
            }
        }
    // Capitalise the first letter of each sentence.
    val out = StringBuilder(fixed.length)
    var capitalizeNext = true
    for (c in fixed) {
        if (capitalizeNext && c.isLetter()) {
            out.append(c.uppercaseChar())
            capitalizeNext = false
        } else {
            out.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '\n') capitalizeNext = true
        }
    }
    return out.toString()
}

/**
 * A cheap, offline guess at whether the "Translate" button is worth showing for [text] when translating
 * into [targetLang] ("en" or "hi"). It only decides visibility; the translate request itself detects the
 * real language. Emoji-only and very short comments never show the button.
 *
 * For English, non-Latin scripts count as foreign, romanised Hindi ("ye video bahut achhi hai") and informal
 * or misspelt English ("gud vdo bro", "u r osm") always get the button, and other Latin text counts as
 * English when enough of its words are common English ones.
 * For Hindi, anything that is not mostly Devanagari gets it.
 */
internal fun looksTranslatable(
    text: String,
    targetLang: String,
): Boolean {
    val letters = text.filter { it.isLetter() }
    if (letters.length < 3) return false
    if (targetLang == "hi") {
        val devanagari = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.DEVANAGARI }
        return devanagari.toDouble() / letters.length < 0.5
    }
    val nonLatin =
        letters.count {
            val script = Character.UnicodeScript.of(it.code)
            script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON
        }
    if (nonLatin.toDouble() / letters.length >= NON_LATIN_RATIO) return true
    if (isRomanizedHindi(text) || isInformalEnglish(text)) return true
    val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
    if (words.size < 3) return false
    return words.count { it in ENGLISH_WORDS }.toDouble() / words.size < ENGLISH_RATIO
}
