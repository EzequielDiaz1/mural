package chat.mural.network

enum class AIProvider(val label: String) {
    OPENAI("OpenAI"), GEMINI("Gemini");

    fun acceptsKey(value: String): Boolean = value.length in 20..500 && value.none(Char::isWhitespace) &&
        when (this) {
            OPENAI -> value.startsWith("sk-")
            GEMINI -> value.startsWith("AIza")
        }
}
