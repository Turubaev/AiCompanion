package dev.catandbunny.ai_companion.utils

/**
 * Утилита для расчета стоимости использования моделей OpenAI
 */
object CostCalculator {
    // Курс доллара к рублю (можно сделать настраиваемым в будущем)
    private const val USD_TO_RUB_RATE = 78.06 // Примерный курс, можно изменить

    // Цены за 1000 токенов в долларах для разных моделей
    // Формат: цена за промпт / цена за ответ
    private val pricingPer1kTokens = mapOf(
        "gpt-3.5-turbo" to Pair(0.0005, 0.0015), // $0.50 / $1.50 за 1M токенов
        "gpt-4" to Pair(0.03, 0.06), // $30 / $60 за 1M токенов
        "gpt-4-turbo-preview" to Pair(0.01, 0.03), // $10 / $30 за 1M токенов
        "gpt-4o" to Pair(0.0025, 0.01), // $2.50 / $10 за 1M токенов
        "gpt-4o-mini" to Pair(0.00015, 0.0006), // $0.15 / $0.60 за 1M токенов
    )

    /**
     * Рассчитывает стоимость запроса на основе модели и использованных токенов
     * @param model Название модели
     * @param promptTokens Количество токенов в промпте
     * @param completionTokens Количество токенов в ответе
     * @return Пара стоимости в долларах и рублях
     */
    fun calculateCost(
        model: String,
        promptTokens: Int,
        completionTokens: Int
    ): Pair<Double, Double> {
        val (promptPricePer1k, completionPricePer1k) = pricingPer1kTokens[model] 
            ?: return Pair(0.0, 0.0) // Если модель не найдена, возвращаем 0

        val promptCost = (promptTokens / 1000.0) * promptPricePer1k
        val completionCost = (completionTokens / 1000.0) * completionPricePer1k
        val totalCostUSD = promptCost + completionCost
        val totalCostRUB = totalCostUSD * USD_TO_RUB_RATE

        return Pair(totalCostUSD, totalCostRUB)
    }

    /**
     * Форматирует стоимость для отображения
     */
    fun formatCost(costUSD: Double, costRUB: Double): String {
        return if (costUSD == 0.0 && costRUB == 0.0) {
            "Бесплатно"
        } else {
            val usdFormatted = String.format("%.6f", costUSD).trimEnd('0').trimEnd('.')
            val rubFormatted = String.format("%.2f", costRUB).trimEnd('0').trimEnd('.')
            "$$usdFormatted ($rubFormatted ₽)"
        }
    }
}
