package com.example.interciclovision

import org.junit.Assert.*
import org.junit.Test

class MobileRulesTest {
    @Test fun controlsMatchNativeDefaultsAndStayInRange() {
        assertArrayEquals(intArrayOf(40, 5, 173, 30, 100, 9, 150),
            ParametrosMoviles.iniciales())
        ParametrosMoviles.controles.forEach { control ->
            for (progress in 0..control.maximoProgreso) {
                val raw = control.desdeProgreso(progress)
                assertTrue(raw in control.minimo..control.maximo)
                assertEquals(0, (raw - control.minimo) % control.paso)
            }
        }
        assertEquals(7, ParametrosMoviles.controles.size)
        val invalid = ParametrosMoviles.iniciales()
        invalid[1] = 4
        assertThrows(IllegalArgumentException::class.java) { ParametrosMoviles.validar(invalid) }
        for (celda in intArrayOf(5, 25)) {
            val fuera = ParametrosMoviles.iniciales().apply { this[5] = celda }
            assertThrows(IllegalArgumentException::class.java) { ParametrosMoviles.validar(fuera) }
        }
        assertThrows(IllegalArgumentException::class.java) { ParametrosMoviles.validar(IntArray(12)) }
    }

    @Test fun cpuPercentageUsesLogicalCpuCapacity() {
        assertEquals(5.0, usoCpuPorcentaje(400, 1000, 8), 0.0001)
        assertEquals(0.0, usoCpuPorcentaje(10, 0, 8), 0.0)
        assertEquals(100.0, usoCpuPorcentaje(9000, 1000, 8), 0.0)
    }
}
