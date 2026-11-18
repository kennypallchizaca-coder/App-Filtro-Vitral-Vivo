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

## Efecto ASCII y código de clase

`Principal.hpp` comparte el preprocesamiento y el dibujo con escritorio. La luminosidad selecciona caracteres de distinta densidad y la respuesta del kernel propio participa en la máscara que selecciona símbolos de contorno. Se conservan gris, mediana, Gaussiano, Sobel combinado, respuesta del kernel y máscara como vistas intermedias.

`Efecto<Mat>` y `Efecto<UMat>` construyen la misma cuadrícula reducida. `cuadricula()` copia o descarga sus mapas; `dibujarAscii` usa `putText` en CPU sobre una imagen BGR del tamaño original. No se requiere fuente ni dependencia adicional de Android. La comparación y el portal conservan `Rect` y `copyTo` de los ejemplos de clase.

## Kernel propio

```text
                 [ -a     -1       0  ]
K = 1/(4+2a)  *  [ -1    4+2a     -1 ]
                 [  0     -1      -a ]
```

La suma es cero: una región uniforme da respuesta cero. `a` regula dos vecinos diagonales. La respuesta conserva signo en CV_32F antes de obtener su magnitud. `pesoPropio` la mezcla con Sobel. La simetría de 180 grados hace coincidir aquí correlación y convolución. El resultado artístico se obtiene de luminosidad y símbolos de contorno, no de una conversión de color tipo cómic.

## Controles

`ParametrosMoviles.kt` define siete controles en el mismo orden que escritorio y JNI: umbral, tamaño Gaussiano, sigma, peso del filtro propio, diagonal del kernel, tamaño de celda y contraste CLAHE. Los decimales viajan como enteros escalados por 100. El Gaussiano avanza de 2 en 2 entre 3 y 51; la celda admite 6..24 píxeles.

Los valores iniciales son `[40, 5, 173, 30, 100, 9, 150]`. Cada cambio publica una copia nueva del arreglo; JNI y C++ validan tamaño y rangos antes de modificar la salida. Un estado guardado antiguo de doce valores se descarta por incompatible y se conservan los valores iniciales.

## Mediciones en vivo

`Mediciones.kt` contiene únicamente los valores de recursos y el cálculo del porcentaje CPU. No hay lista de muestras, formato CSV, exportador, selector de destino ni hilo de escritura.

- CPU: Mat con OpenCL desactivado.
- GPU: preprocesamiento UMat con OpenCL habilitado y dispositivo GPU comprobado; dibujo de caracteres en CPU. `ocl::finish` espera el trabajo para medir tiempos reales.
- El tiempo total de cada ruta incluye conversiones, dibujo ASCII en CPU y preparación de su vista; GPU también carga/descarga. Los subtiempos de filtro y descarga terminan antes del dibujo y no lo atribuyen a la GPU. No incluye la copia final a JNI ni captura/dibujo de cámara.
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

## Portal ASCII

`prepararVista` convierte el resultado a RGBA. Solo en **Portal ASCII**, `aplicarPortal` usa una máscara con `copyTo` para recuperar el original fuera del rectángulo central; después `rectangle` dibuja el marco. CPU y GPU siguen procesando el fotograma entero.

La vista original conserva el procesamiento cronometrado, pero no descarga mapas ni dibuja caracteres que no se van a mostrar. Las vistas de comparación, ASCII y portal dibujan la cuadrícula; las intermedias muestran directamente la etapa elegida. No se usa segmentación de personas, VTK ni ARCore en Android.
