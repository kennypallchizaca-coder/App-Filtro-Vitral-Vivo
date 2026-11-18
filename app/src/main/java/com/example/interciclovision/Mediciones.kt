package com.example.interciclovision

data class RecursosMedidos(val fps: Double = Double.NaN, val cpuPorcentaje: Double = Double.NaN,
                           val ramPssMiB: Double = Double.NaN, val ramLibreMiB: Double = Double.NaN,
                           val procesoMs: Double = Double.NaN)

// Asistencia de IA (OpenAI Codex): ventanas reiniciables para no atribuir trabajo previo al modo nuevo.
internal class VentanaMedicion {
    private var marca: Long? = null
    private var baseCpu = 0L
    private var fotogramas = 0
    private var sumaMs = 0.0

    fun reiniciar() {
        marca = null
        fotogramas = 0
        sumaMs = 0.0
    }

    fun agregar(ahora: Long, procesoMs: Double, nucleos: Int, cpuMs: () -> Long): RecursosMedidos? {
        val inicio = marca
        if (inicio == null) {
            marca = ahora
            baseCpu = cpuMs()
            return null
        }
        fotogramas++
        sumaMs += procesoMs
        val intervalo = ahora - inicio
        if (intervalo < 1000) return null
        val cpu = cpuMs()
        val muestra = RecursosMedidos(
            fps = fotogramas * 1000.0 / intervalo,
            cpuPorcentaje = usoCpuPorcentaje(cpu - baseCpu, intervalo, nucleos),
            procesoMs = sumaMs / fotogramas)
        reiniciar()
        marca = ahora
        baseCpu = cpu
        return muestra
    }
}

fun usoCpuPorcentaje(cpuMs: Long, paredMs: Long, nucleos: Int): Double =
    if (paredMs <= 0 || nucleos <= 0) 0.0
    else (100.0 * cpuMs / paredMs / nucleos).coerceIn(0.0, 100.0)
