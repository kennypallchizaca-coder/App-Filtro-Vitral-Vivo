package com.example.interciclovision

import org.junit.Assert.*
import org.junit.Test

class MobileRulesTest {
    @Test fun anOldCameraResultCannotEnterANewMeasurement() {
        val record = RegistroMediciones()
        record.iniciar(ParametrosMoviles.iniciales(), 1)
        val oldId = record.generacion
        record.detener()
        val next = ParametrosMoviles.iniciales().apply { this[0] = 41 }
        record.iniciar(next, 1)
        val times = doubleArrayOf(8.0, 4.0, 0.5, 2.8, 0.7, 1.0)
        repeat(11) { record.agregar(oldId, times, 64, 48, RecursosMedidos(), "GPU", "old") }
        assertEquals(0, record.pares)
        assertEquals("", record.csv())
    }

    @Test fun controlsMatchNativeDefaultsAndStayInRange() {
        assertArrayEquals(intArrayOf(40, 5, 173, 30, 100, 8, 1, 125, 100, 115, 1, 150),
            ParametrosMoviles.iniciales())
        ParametrosMoviles.controles.forEach { control ->
            for (progress in 0..control.maximoProgreso) {
                val raw = control.desdeProgreso(progress)
                assertTrue(raw in control.minimo..control.maximo)
                assertEquals(0, (raw - control.minimo) % control.paso)
            }
        }
        val invalid = ParametrosMoviles.iniciales()
        invalid[1] = 4
        assertThrows(IllegalArgumentException::class.java) { ParametrosMoviles.validar(invalid) }
    }

    @Test fun cpuPercentageUsesLogicalCpuCapacity() {
        assertEquals(5.0, usoCpuPorcentaje(400, 1000, 8), 0.0001)
        assertEquals(0.0, usoCpuPorcentaje(10, 0, 8), 0.0)
        assertEquals(100.0, usoCpuPorcentaje(9000, 1000, 8), 0.0)
    }

    @Test fun pairedCollectionExcludesWarmupAndStopsAtBound() {
        val record = RegistroMediciones()
        val values = ParametrosMoviles.iniciales()
        record.iniciar(values, 0)
        values[0] = 255
        assertEquals(40, record.valores()[0])
        val times = doubleArrayOf(8.0, 4.0, 0.5, 2.8, 0.7, 1.0)
        repeat(10) { record.agregar(record.generacion, times, 640, 480, RecursosMedidos(), "Adreno, \"Test\"", "2026-11-15T00:00:00Z") }
        assertEquals("", record.csv())
        repeat(120) { record.agregar(record.generacion, times, 640, 480, RecursosMedidos(30.0, 5.0, 80.0, 2048.0), "Adreno, \"Test\"", "2026-11-15T00:00:00Z") }
        assertFalse(record.activa)
        assertEquals(120, record.pares)
        assertEquals(241, record.csv().trim().lines().size)
        assertEquals(8.0, record.promedioCpuMs, 0.0)
        assertEquals(4.0, record.promedioGpuMs, 0.0)
        assertTrue(record.csv().contains("\"Adreno, \"\"Test\"\"\""))
        assertTrue(record.csv().contains("1.730000"))
        val before = record.csv()
        record.agregar(record.generacion, times, 640, 480, RecursosMedidos(), "GPU", "later")
        assertEquals(before, record.csv())
    }

    @Test fun unavailableGpuNeverCreatesFakeGpuRows() {
        val record = RegistroMediciones()
        record.iniciar(ParametrosMoviles.iniciales(), 1)
        repeat(11) { record.agregar(record.generacion, doubleArrayOf(8.0, Double.NaN, 0.0, 0.0, 0.0, 0.0),
            320, 240, RecursosMedidos(), "CPU", "2026-11-15T00:00:00Z") }
        record.detener()
        assertEquals(2, record.csv().trim().lines().size)
        assertFalse(record.csv().contains("UMat_OpenCL"))
        assertTrue(record.promedioGpuMs.isNaN())
        val columns = record.csv().trim().lines()[1].split(',')
        for (i in 10..13) assertEquals("", columns[i])
    }
}
