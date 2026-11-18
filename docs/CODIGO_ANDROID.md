# Código de la aplicación Android

## Recorrido de un fotograma

1. `MainActivity.kt` prepara permiso, cámara OpenCV y controles.
2. La cámara entrega un Mat RGBA prestado; no se libera ni altera esa entrada.
3. La actividad toma los parámetros y la vista actuales y llama a JNI.
4. `native-lib.cpp` valida direcciones y parámetros, serializa procesamiento/reinicio y traduce excepciones.
5. `ProcesadorAndroid.hpp` convierte RGBA a BGR y utiliza el mismo `Efecto<Mat>` / `Efecto<UMat>` del escritorio.
6. Ambas rutas se cronometran sobre el mismo fotograma; el selector CPU/GPU decide qué resultado se muestra. Si no hay GPU utilizable, se informa el respaldo CPU.
7. Se vuelve a RGBA y se conserva el tamaño de entrada requerido por el Bitmap.
8. La comparación encaja original y efecto completos: arriba/abajo o izquierda/derecha según las dimensiones de cámara. No estira ni recorta las imágenes.
9. Los textos de tiempos, FPS y recursos se actualizan cada medio segundo, sin guardar archivos.

## Efecto Spiderman y código de clase

`Principal.hpp` conserva la misma lógica en las dos plataformas. Adapta CLAHE en luminosidad Lab y la mediana 3×3 de los ejemplos 3, 5 y 9. El kernel propio, la paleta, el halftone, el rayado y el glitch RGB completan el efecto Spiderman.

`detectarContornos` reúne el bloque Gaussiano → Sobel → `convertScaleAbs` → `addWeighted` del Ejemplo 6 (Unidad 2, `Principal.cpp`, líneas 65–82) y después combina el kernel propio. La comparación y el portal adaptan el uso de `Rect` y `copyTo` de los Ejemplos 6 y 7. El selector muestra estas etapas sin recalcularlas.

## Kernel propio

```text
                 [ -a     -1       0  ]
K = 1/(4+2a)  *  [ -1    4+2a     -1 ]
                 [  0     -1      -a ]
```

La suma es cero: una región uniforme da respuesta cero. `a` regula dos vecinos diagonales. La respuesta conserva signo en CV_32F antes de obtener su magnitud. `pesoPropio` la mezcla con Sobel. La simetría de 180 grados hace coincidir aquí correlación y convolución. Spiderman es el nombre de la combinación artística, no un kernel estándar publicado.

## Controles

`ParametrosMoviles.kt` define una sola lista con los doce nombres, valores y rangos. Los valores decimales se representan como enteros escalados por 100 y JNI los convierte a double. El tamaño Gaussiano avanza de 2 en 2 entre 3 y 51. Los controles publican una copia nueva de los parámetros y C++ vuelve a validarlos.

Los valores iniciales son umbral40, Gauss5, sigma1.73, peso0.30, diagonal1, niveles8, radio1, saturación1.25, brillo1, neón1.15, glitch1 y CLAHE1.5.

## Mediciones en vivo

`Mediciones.kt` contiene únicamente los valores de recursos y el cálculo del porcentaje CPU. No hay lista de muestras, formato CSV, exportador, selector de destino ni hilo de escritura.

- CPU: Mat con OpenCL desactivado.
- GPU: UMat con OpenCL habilitado y dispositivo GPU comprobado. `ocl::finish` espera el trabajo para medir tiempos reales.
- El tiempo de cada ruta incluye conversiones y preparación de su vista; GPU también carga/descarga. No incluye la copia final a JNI ni captura/dibujo de cámara.
- FPS: fotogramas entregados por tiempo monotónico, no `1000/ms`.
- RAM del proceso: PSS convertido de KiB a MiB. No equivale a VRAM.
- RAM libre: ActivityManager, bytes convertidos a MiB.
- CPU: tiempo CPU dividido por tiempo de pared y procesadores lógicos, normalizado a 0..100%.
- Recursos: muestreo aproximadamente cada segundo. Antes de una muestra válida se muestra una raya.

Ambas rutas se ejecutan secuencialmente para compararlas; sus recursos no son dos procesos independientes. T-API puede usar CPU en operaciones no compatibles y no se promete aceleración universal.

## Permisos y ciclo de vida

Sin permiso se explica el acceso necesario. Tras denegación permanente se puede abrir Ajustes. Al reanudar se verifica el permiso; al pausar se cierra la cámara y se esperan sus callbacks antes de liberar las matrices. No se comparte una instancia mutable de Efecto entre procesamientos paralelos.

La rotación conserva parámetros, modo, vista y cámara. La tarea de interfaz recibe valores escalares, no un Mat prestado. Un aviso tras ocho segundos sin fotogramas ofrece reinicio manual, sin bucles de reinicio ocultos.

## Pruebas

Las pruebas nativas comprueban diez vistas, imágenes en retrato/paisaje, RGBA del mismo tamaño, igualdad CPU con el efecto y entradas inválidas. También comprueban comparación de tiempos con salida CPU y con salida GPU o respaldo real.

Las pruebas JVM conservan valores/rangos y porcentaje CPU. Python comprueba permisos, controles, contraste, empaquetado y ausencia de exportación. La prueba instrumentada JNI requiere ejecutar su APK en un dispositivo autorizado; compilar no sustituye probar la cámara del S25.

## Portal cómic

`prepararVista` convierte el resultado a RGBA. Solo en **Portal cómic**, `aplicarPortal` usa una máscara con `copyTo` para recuperar el original fuera del rectángulo central; después `rectangle` dibuja el marco. CPU y GPU siguen procesando el fotograma entero.

El acabado compartido añade mediana 3×3 a la máscara de tinta, aclara los puntos (factor 0,80), los limita a luminosidad 70–155, reserva el rayado a 25–69 y limita el RGB desplazado a una dilatación de la máscara de contornos. No usa segmentación de personas ni modelos adicionales.
