# Código de la aplicación Android

## Recorrido de un fotograma

1. `MainActivity.kt` prepara permiso, cámara OpenCV y controles.
2. La cámara entrega un Mat RGBA prestado; no se libera ni altera esa entrada.
3. La actividad toma los parámetros y la vista actuales y llama a JNI.
4. `native-lib.cpp` valida direcciones y parámetros, serializa procesamiento/reinicio y traduce excepciones.
5. `ProcesadorAndroid.hpp` convierte RGBA a BGR y utiliza el mismo `Efecto<Mat>` / `Efecto<UMat>` del escritorio.
6. La aplicación ejecuta el modo seleccionado, CPU/Mat o GPU/OpenCL. Al cambiar de modo arranca una ventana nueva y conserva la última muestra completa de cada ruta. El respaldo CPU se indica y no genera una muestra OpenCL.
7. Se vuelve a RGBA y se conserva el tamaño de entrada requerido por el Bitmap.
8. La comparación encaja original y efecto completos: arriba/abajo o izquierda/derecha según las dimensiones de cámara. No estira ni recorta las imágenes.
9. Los textos de tiempos, FPS y recursos se actualizan cada medio segundo, sin guardar archivos.

## Vitral Vivo y código de clase

`Principal.hpp` comparte preprocesamiento y dibujo con escritorio. El color sale del fotograma original; la máscara de Sobel y del kernel propio realza subdivisiones fijas. Las etapas intermedias siguen disponibles.

`Efecto<Mat>` y `Efecto<UMat>` construyen los mismos mapas reducidos. `dibujarVitral` rellena triángulos y dibuja uniones en CPU. La comparación y el portal siguen usando `Rect` y `copyTo`.

## Kernel propio

```text
                 [ -a     -1       0  ]
K = 1/(4+2a)  *  [ -1    4+2a     -1 ]
                 [  0     -1      -a ]
```

La suma es cero: una región uniforme da respuesta cero. `a` regula dos vecinos diagonales. La respuesta conserva signo en CV_32F antes de tomar su magnitud y multiplicar por 12 para combinar con Sobel. La simetría hace coincidir correlación y convolución.

## Controles

`ParametrosMoviles.kt` define siete controles en el mismo orden que escritorio y JNI: umbral, tamaño Gaussiano, sigma, peso del filtro propio, diagonal del kernel, escala del vitral y contraste CLAHE. Los decimales viajan como enteros escalados por 100. El tamaño Gaussiano es impar entre 3 y 51. La escala va de 6 a 24 y el lado del bloque base mide cerca de tres veces ese valor.

Los valores iniciales son `[40, 5, 173, 30, 100, 9, 150]`. Cada cambio publica una copia nueva del arreglo; JNI y C++ validan tamaño y rangos antes de modificar la salida. Un estado guardado antiguo de doce valores se descarta por incompatible y se conservan los valores iniciales.

## Mediciones en vivo

`Mediciones.kt` completa ventanas de al menos un segundo y calcula el tiempo medio por fotograma, FPS y porcentaje de CPU. La actividad añade PSS y RAM libre al cerrar cada ventana. Las dos últimas muestras completas (una por modo) solo viven en memoria; no hay exportación CSV.

- CPU: Mat con OpenCL desactivado.
- GPU: preprocesamiento UMat con un dispositivo OpenCL comprobado; dibujo del vitral en CPU. `ocl::finish` espera el trabajo antes de cronometrar.
- El tiempo total incluye conversiones, dibujo y preparación de la vista; en OpenCL también las transferencias. No incluye captura, visualización de cámara ni la última copia JNI.
- FPS: fotogramas entregados por tiempo monotónico, no `1000/ms`.
- RAM del proceso: PSS convertido de KiB a MiB. No equivale a VRAM.
- RAM libre: ActivityManager, bytes convertidos a MiB.
- CPU: tiempo CPU dividido por tiempo de pared y procesadores lógicos, normalizado a 0..100%.
- Recursos: muestreo aproximadamente cada segundo. Antes de una muestra válida se muestra una raya.

La app ejecuta una ruta por vez. Al cambiar de modo reinicia procesador y ventana; una muestra completa queda disponible por modo. El respaldo CPU nunca se anota como OpenCL. PSS es memoria del proceso, no VRAM.

## Permisos y ciclo de vida

Sin permiso se explica el acceso necesario. Tras denegación permanente se puede abrir Ajustes. Al reanudar se verifica el permiso; al pausar se cierra la cámara y se esperan sus callbacks antes de liberar las matrices. No se comparte una instancia mutable de Efecto entre procesamientos paralelos.

La rotación conserva parámetros, modo, vista y cámara. La tarea de interfaz recibe valores escalares, no un Mat prestado. Un aviso tras ocho segundos sin fotogramas ofrece reinicio manual, sin bucles de reinicio ocultos.

## Pruebas

Las pruebas nativas comprueban diez vistas, imágenes en retrato/paisaje, RGBA del mismo tamaño, igualdad CPU con el efecto y entradas inválidas. También comprueban comparación de tiempos con salida CPU y con salida GPU o respaldo real.

Las pruebas JVM comprueban parámetros, porcentaje CPU y descarte de ventanas parciales. Python comprueba controles, permisos y empaquetado. La prueba instrumentada requiere un S25 autorizado; compilarla no la ejecuta.

## Portal Vitral

`prepararVista` convierte a RGBA. **Portal Vitral** devuelve el original fuera de un rectángulo mediante una máscara y marca el marco con `rectangle`.

La vista original conserva el procesamiento cronometrado, pero no descarga ni dibuja el vitral. Comparación, Vitral Vivo y Portal Vitral sí dibujan la malla. Las intermedias muestran la etapa elegida.
