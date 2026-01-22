package dev.catandbunny.ai_companion.utils

/**
 * Утилита для приблизительного подсчета токенов в тексте
 * Использует приблизительную формулу на основе правил OpenAI
 */
object TokenCounter {
    /**
     * Подсчитывает приблизительное количество токенов в тексте
     * 
     * Алгоритм основан на приблизительных правилах:
     * - Для английского текста: ~4 символа = 1 токен
     * - Для русского текста: ~2 символа = 1 токен
     * - Для смешанного текста: используется среднее значение
     * 
     * @param text Текст для подсчета токенов
     * @return Приблизительное количество токенов
     */
    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        
        // Определяем язык текста
        val language = detectLanguage(text)
        
        // Подсчитываем токены в зависимости от языка
        return when (language) {
            Language.ENGLISH -> {
                // Для английского: ~4 символа = 1 токен
                (text.length / 4.0).toInt().coerceAtLeast(1)
            }
            Language.RUSSIAN -> {
                // Для русского: ~2 символа = 1 токен
                (text.length / 2.0).toInt().coerceAtLeast(1)
            }
            Language.MIXED -> {
                // Для смешанного: используем среднее значение (~3 символа = 1 токен)
                (text.length / 3.0).toInt().coerceAtLeast(1)
            }
            Language.OTHER -> {
                // Для других языков: используем среднее значение (~3 символа = 1 токен)
                (text.length / 3.0).toInt().coerceAtLeast(1)
            }
        }
    }
    
    /**
     * Определяет язык текста
     */
    private fun detectLanguage(text: String): Language {
        val cyrillicPattern = Regex("[А-Яа-яЁё]")
        val latinPattern = Regex("[A-Za-z]")
        
        val cyrillicCount = cyrillicPattern.findAll(text).count()
        val latinCount = latinPattern.findAll(text).count()
        
        return when {
            cyrillicCount > 0 && latinCount == 0 -> Language.RUSSIAN
            latinCount > 0 && cyrillicCount == 0 -> Language.ENGLISH
            cyrillicCount > 0 && latinCount > 0 -> Language.MIXED
            else -> Language.OTHER
        }
    }
    
    private enum class Language {
        ENGLISH,
        RUSSIAN,
        MIXED,
        OTHER
    }
}
