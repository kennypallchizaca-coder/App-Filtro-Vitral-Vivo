package com.example.interciclovision

data class RecursosMedidos(val fps: Double = Double.NaN, val cpuPorcentaje: Double = Double.NaN,
                           val ramPssMiB: Double = Double.NaN, val ramLibreMiB: Double = Double.NaN)

fun usoCpuPorcentaje(cpuMs: Long, paredMs: Long, nucleos: Int): Double =
    if (paredMs <= 0 || nucleos <= 0) 0.0
    else (100.0 * cpuMs / paredMs / nucleos).coerceIn(0.0, 100.0)
