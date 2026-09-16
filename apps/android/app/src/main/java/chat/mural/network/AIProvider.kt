package chat.mural.network

enum class AIProvider(val label: String) {
    OPENAI("OpenAI"), GEMINI("Gemini");

    fun acceptsKey(value: String): Boolean {
        if (value.length !in 20..500 || value.any(Char::isWhitespace)) return false
        return when (this) {
            OPENAI -> value.startsWith("sk-")
            // Google AI Studio now creates authorization keys (AQ.) by default.
            // Restricted standard keys (AIza) remain supported during migration.
            GEMINI -> when {
                value.startsWith("AQ.") -> value.drop(3).all { it.isGoogleKeyCharacter() }
                value.startsWith("AIza") -> value.drop(4).all { it.isGoogleKeyCharacter() }
                else -> false
            }
        }
    }
}

private fun Char.isGoogleKeyCharacter(): Boolean = isLetterOrDigit() || this == '-' || this == '_'
