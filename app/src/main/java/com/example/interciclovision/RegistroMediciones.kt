package com.example.interciclovision

import java.util.Locale

data class RecursosMedidos(val fps: Double = Double.NaN, val cpuPorcentaje: Double = Double.NaN,
                           val ramPssMiB: Double = Double.NaN, val ramLibreMiB: Double = Double.NaN)

fun usoCpuPorcentaje(cpuMs: Long, paredMs: Long, nucleos: Int): Double =
    if (paredMs <= 0 || nucleos <= 0) 0.0
    else (100.0 * cpuMs / paredMs / nucleos).coerceIn(0.0, 100.0)

// The activity synchronizes access between its UI and camera callback.
class RegistroMediciones {
    var generacion = 0L
        private set
    var activa = false
        private set
    var pares = 0
        private set
    var vista = 0
        private set
    private var calentamiento = 10
    private var parametros = ParametrosMoviles.iniciales()
    private val filas = mutableListOf<String>()
    private var sumaCpu = 0.0
    private var sumaGpu = 0.0
    private var muestrasGpu = 0
    val promedioCpuMs get() = if (pares == 0) Double.NaN else sumaCpu / pares
    val promedioGpuMs get() = if (muestrasGpu == 0) Double.NaN else sumaGpu / muestrasGpu

    fun iniciar(valores: IntArray, vista: Int) {
        ParametrosMoviles.validar(valores)
        require(vista in 0..8)
        generacion++
        parametros = valores.copyOf()
        this.vista = vista
        calentamiento = 10
        pares = 0
        sumaCpu = 0.0
        sumaGpu = 0.0
        muestrasGpu = 0
        filas.clear()
        activa = true
    }

    fun valores() = parametros.copyOf()
    fun detener() { activa = false }

    fun agregar(id: Long, t: DoubleArray, ancho: Int, alto: Int, recursos: RecursosMedidos,
                dispositivo: String, instante: String) {
        // Ignore a callback that finished after Stop followed by a new run.
        if (!activa || id != generacion) return
        require(t.size == 6 && t[0].isFinite() && t[0] >= 0 && ancho > 0 && alto > 0)
        if (calentamiento > 0) {
            calentamiento--
            return
        }
        pares++
        sumaCpu += t[0]
        filas.add(fila(false, t, ancho, alto, recursos, dispositivo, instante))
        if (t[1].isFinite() && t[1] >= 0) {
            muestrasGpu++
            sumaGpu += t[1]
            filas.add(fila(true, t, ancho, alto, recursos, dispositivo, instante))
        }
        if (pares == 120) activa = false
    }

    fun csv(): String {
        if (filas.isEmpty()) return ""
        val cabecera = listOf("timestamp_utc", "frame", "modo", "ancho", "alto", "vista",
            "total_ms", "carga_ms", "filtro_gpu_ms", "descarga_ms", "fps_vista",
            "cpu_proceso_pct", "ram_pss_mib", "ram_libre_mib", "dispositivo") +
            ParametrosMoviles.controles.map { it.clave }
        return cabecera.joinToString(",") + "\n" + filas.joinToString("\n") + "\n"
    }

    private fun fila(gpu: Boolean, t: DoubleArray, ancho: Int, alto: Int,
                     r: RecursosMedidos, dispositivo: String, instante: String): String {
        val datos = listOf(escapar(instante), pares.toString(), if (gpu) "UMat_OpenCL" else "CPU_Mat",
            ancho.toString(), alto.toString(), vista.toString(), numero(t[if (gpu) 1 else 0]),
            if (gpu) numero(t[2]) else "", if (gpu) numero(t[3]) else "",
            if (gpu) numero(t[4]) else "", numero(r.fps), numero(r.cpuPorcentaje),
            numero(r.ramPssMiB), numero(r.ramLibreMiB), escapar(dispositivo)) +
            ParametrosMoviles.controles.mapIndexed { i, control -> control.valorCsv(parametros[i]) }
        return datos.joinToString(",")
    }

    private fun numero(valor: Double) = if (valor.isFinite()) String.format(Locale.US, "%.6f", valor) else ""
    private fun escapar(texto: String) = "\"" + texto.replace("\"", "\"\"") + "\""
}
