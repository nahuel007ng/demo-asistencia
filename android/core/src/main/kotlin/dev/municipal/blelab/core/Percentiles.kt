package dev.municipal.blelab.core

import kotlin.math.ceil

object Percentiles {
    /**
     * Nearest-rank. La posición es ceil(p/100*N), contando desde 1.
     * Se resta una tolerancia mínima para que un producto binario como 5.0000000001 no suba de rango.
     */
    fun nearestRank(values: List<Long>, percentile: Double): Long? {
        if (values.isEmpty()) return null
        require(percentile in 0.0..100.0)
        val sorted = values.sorted()
        val rank = ceil(percentile / 100.0 * sorted.size - 1e-9).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun mean(values: List<Long>): Double? {
        if (values.isEmpty()) return null
        return values.sum().toDouble() / values.size
    }
}
