package com.example.interciclovision

import java.util.Locale

data class ControlFiltro(val nombre: String, val clave: String, val minimo: Int,
                         val maximo: Int, val paso: Int, val inicial: Int, val escala: Int = 1) {
    val maximoProgreso get() = (maximo - minimo) / paso
    fun desdeProgreso(progreso: Int): Int {
        require(progreso in 0..maximoProgreso)
        return minimo + progreso * paso
    }
    fun mostrar(valor: Int): String = if (escala == 1) valor.toString()
        else String.format(Locale.US, "%.2f", valor.toDouble() / escala)
    fun valorCsv(valor: Int): String = String.format(Locale.US, "%.6f", valor.toDouble() / escala)
}

object ParametrosMoviles {
    val controles = listOf(
        ControlFiltro("Umbral", "umbral", 0, 255, 1, 40),
        ControlFiltro("Kernel Gaussiano", "tamGauss", 3, 51, 2, 5),
        ControlFiltro("Sigma", "sigma", 10, 1000, 1, 173, 100),
        ControlFiltro("Peso del filtro propio", "pesoPropio", 0, 100, 1, 30, 100),
        ControlFiltro("Diagonal del kernel", "diagonal", 0, 300, 1, 100, 100),
        ControlFiltro("Niveles de color", "niveles", 2, 16, 1, 8),
        ControlFiltro("Radio halftone", "radioPuntos", 0, 3, 1, 1),
        ControlFiltro("Saturación", "saturacion", 0, 300, 1, 125, 100),
        ControlFiltro("Brillo", "brillo", 0, 300, 1, 100, 100),
        ControlFiltro("Neón", "neon", 0, 300, 1, 115, 100),
        ControlFiltro("Glitch RGB", "desplazamiento", 0, 3, 1, 1),
        ControlFiltro("Contraste CLAHE", "contraste", 10, 800, 1, 150, 100)
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
