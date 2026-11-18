package com.example.interciclovision

import java.util.Locale

data class ControlFiltro(val nombre: String, val minimo: Int,
                         val maximo: Int, val paso: Int, val inicial: Int, val escala: Int = 1) {
    val maximoProgreso get() = (maximo - minimo) / paso
    fun desdeProgreso(progreso: Int): Int {
        require(progreso in 0..maximoProgreso)
        return minimo + progreso * paso
    }
    fun mostrar(valor: Int): String = if (escala == 1) valor.toString()
        else String.format(Locale.US, "%.2f", valor.toDouble() / escala)
}

// Asistencia de IA: OpenAI Codex adaptó los rangos visibles a los parámetros ASCII.
object ParametrosMoviles {
    val controles = listOf(
        ControlFiltro("Umbral", 0, 255, 1, 40),
        ControlFiltro("Kernel Gaussiano", 3, 51, 2, 5),
        ControlFiltro("Sigma", 10, 1000, 1, 173, 100),
        ControlFiltro("Peso del filtro propio", 0, 100, 1, 30, 100),
        ControlFiltro("Diagonal del kernel", 0, 300, 1, 100, 100),
        ControlFiltro("Tamaño de celda", 6, 24, 1, 9),
        ControlFiltro("Contraste CLAHE", 10, 800, 1, 150, 100)
    )
    fun iniciales() = controles.map { it.inicial }.toIntArray()
    fun validar(valores: IntArray) {
        require(valores.size == controles.size)
        controles.forEachIndexed { i, control ->
            require(valores[i] in control.minimo..control.maximo &&
                (valores[i] - control.minimo) % control.paso == 0)
        }
    }
}
