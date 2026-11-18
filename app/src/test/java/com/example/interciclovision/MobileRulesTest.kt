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

    @Test fun switchingModeDiscardsThePartialWindowAndWarmupFrame() {
        val ventana = VentanaMedicion()
        var cpu = 100L
        assertNull(ventana.agregar(10, 100.0, 4) { cpu })
        assertNull(ventana.agregar(510, 20.0, 4) { cpu })
        ventana.reiniciar()
        cpu = 1000L
        assertNull(ventana.agregar(600, 200.0, 4) { cpu })
        assertNull(ventana.agregar(1100, 4.0, 4) { cpu })
        cpu = 1200L
        val muestra = checkNotNull(ventana.agregar(1600, 6.0, 4) { cpu })
        assertEquals(5.0, muestra.procesoMs, 0.0001)
        assertEquals(2.0, muestra.fps, 0.0001)
        assertEquals(5.0, muestra.cpuPorcentaje, 0.0001)
    }

    @Test fun cpuPercentageUsesLogicalCpuCapacity() {
        assertEquals(5.0, usoCpuPorcentaje(400, 1000, 8), 0.0001)
        assertEquals(0.0, usoCpuPorcentaje(10, 0, 8), 0.0)
        assertEquals(100.0, usoCpuPorcentaje(9000, 1000, 8), 0.0)
    }
}
