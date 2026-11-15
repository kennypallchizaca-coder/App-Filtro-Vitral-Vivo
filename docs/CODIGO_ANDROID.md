# Código de la aplicación Android

## Recorrido de un fotograma

1. `MainActivity.kt` prepara la interfaz, consulta el permiso y configura `JavaCamera2View`.
2. OpenCV entrega un `Mat` RGBA prestado a `onCameraFrame`. La aplicación no libera ni altera esa entrada.
3. La actividad toma una instantánea de los doce valores. JNI recibe las direcciones de entrada/salida, la vista y el modo elegido.
4. `native-lib.cpp` comprueba los argumentos, serializa procesamiento/reinicio con un mutex y traduce excepciones C++ a excepciones Java.
5. `ProcesadorAndroid.hpp` transforma RGBA a BGR y llama al mismo `Efecto<Mat>` o `Efecto<UMat>` del escritorio. La copia local `Principal.hpp` conserva el algoritmo y sus nombres.
6. Se selecciona la imagen final o un intermedio y se vuelve a RGBA. La salida mantiene las dimensiones de entrada para el Bitmap de la cámara.
7. La vista de comparación conserva la mitad izquierda original y muestra el efecto en la derecha. Las dos mitades pertenecen al mismo fotograma y mantienen sus coordenadas.
8. La actividad devuelve la matriz de salida y publica textos de estado cada medio segundo, no una tarea de interfaz por cada píxel.

## Filtros reutilizados

`Principal.hpp` conserva CLAHE en luminosidad Lab, mediana 3x3, Gaussiano, Sobel X/Y y su combinación, el filtro propio, umbral, LUT de colores, paleta, puntos halftone, rayado, HSV y glitch RGB. Los contornos se calculan antes de dibujar la trama para no detectar los puntos como bordes nuevos.

El Gaussiano/Sobel sigue la secuencia de clase que ya utiliza el escritorio. No se ha sustituido por una red neuronal ni una biblioteca de filtros diferente.

## Kernel propio

Con parámetro diagonal `a`, el kernel es:

```text
                 [ -a     -1       0  ]
K = 1/(4+2a)  *  [ -1    4+2a     -1 ]
                 [  0     -1      -a ]
```

La suma es cero: una región uniforme produce respuesta cero. Los vecinos horizontales/verticales detectan cambios y `a` regula las dos diagonales. Se conserva una respuesta flotante con signo antes de tomar su magnitud. `pesoPropio` mezcla esa magnitud con el Sobel combinado; no reemplaza arbitrariamente el video por el kernel. Su simetría de 180 grados hace coincidir aquí correlación y convolución.

## Parámetros

`ParametrosMoviles.kt` define una sola lista para construir los SeekBars y exportar sus nombres/valores. Los decimales viajan como enteros escalados por 100 y se convierten a double en JNI. El kernel usa pasos de 2 entre 3 y 51. La actividad publica una copia nueva de la tabla al cambiar un control, mientras C++ vuelve a validar todos los rangos antes de tocar la salida.

Los defaults son los del escritorio: umbral40, Gauss5, sigma1.73, peso0.30, diagonal1, niveles8, radio1, saturación1.25, brillo1, neón1.15, glitch1 y CLAHE1.5.

## CPU y OpenCL

La ruta CPU desactiva OpenCL y usa `Efecto<Mat>`. La GPU comprueba OpenCL y que el dispositivo por defecto sea realmente GPU, activa T-API, sube BGR a UMat y ejecuta `Efecto<UMat>`. Las llamadas `ocl::finish` cierran el trabajo antes de leer los tiempos. La descarga deja una imagen CPU que Android puede dibujar.

Si el dispositivo o la operación fallan, se conserva la ruta CPU y se informa en pantalla. Las columnas GPU se omiten cuando no hay una medición GPU válida. El texto «OpenCL disponible» solo indicaba disponibilidad del runtime; la ruta nueva exige además un dispositivo GPU y procesamiento real.

## Tiempo, memoria y CSV

`RegistroMediciones.kt` conserva como máximo 120 entradas emparejadas: 10 entradas previas se usan como calentamiento y no llegan al CSV. Los parámetros y la vista se congelan durante el registro. Cada sesión tiene un identificador: se ignora un callback atrasado de una sesión que ya se detuvo, aunque se haya iniciado otra. Cada entrada usa CPU primero y GPU después, secuencialmente, sobre la misma imagen; no es una prueba de ejecución simultánea ni elimina los efectos térmicos del teléfono.

Los tiempos se obtienen con `steady_clock`. El total de cada ruta incluye su conversión RGBA/BGR, filtros, lectura del intermedio y conversión de salida. La carga/filtrado/descarga GPU también se separan. La copia final al Mat JNI y captura/pantalla se excluyen; por eso el FPS de vista se mide con callbacks/segundo y no como `1000/ms`.

- RAM del proceso: `Debug.getPss`, KiB convertidos a MiB. Incluye memoria proporcional del proceso, no equivale a VRAM dedicada.
- RAM disponible: `ActivityManager.MemoryInfo.availMem`, bytes convertidos a MiB.
- CPU: diferencia de `Process.getElapsedCpuTime` sobre tiempo monotónico y núcleos lógicos; porcentaje normalizado, aproximado y del proceso entero, no solo del filtro.
- Muestreo de recursos: aproximadamente1Hz. Antes de una muestra válida se utiliza un campo vacío o una raya.
- Exportación: `CreateDocument("text/csv")`; el usuario elige la ubicación y no se pide acceso general al almacenamiento. Los textos con comillas/comas se escapan correctamente. La escritura trabaja en un hilo separado con una copia del CSV, para no congelar la interfaz si el proveedor de documentos es lento; el registro se conserva ante un error y se confirma cuando termina.

## Ciclo de vida

Sin permiso se muestra una explicación y un botón. Después de una denegación permanente el botón abre los ajustes de la aplicación. Al reanudar se revisa otra vez el permiso. Al pausar se deshabilita la cámara, se espera el cierre de sus callbacks y se liberan las matrices/estado nativo. No se comparte un Efecto mutable entre procesamiento paralelo.

La rotación recrea la actividad con valores, vista, cámara y CSV preservados. Una medición interrumpida se detiene y permite guardar lo capturado; no se presenta como120 pares completos. La superficie de controles se apila en vertical y se coloca al lado de la vista en horizontal, respetando barras/corte de pantalla.

## Pruebas

Las pruebas C++ host comprueban el contrato RGBA, las nueve vistas, la igualdad exacta CPU con el efecto reutilizado, rechazo de parámetros inválidos y GPU o fallback. Las pruebas JVM cubren valores/rangos, kernel impar, CPU porcentual, calentamiento, límite del registro y CSV. Las comprobaciones Python validan AAR/ELF/manifest, controles y contrastes básicos. El APK de pruebas contiene un recorrido JNI para ejecutarlo en Android cuando se autorice esa prueba.

Permisos reales, orientación física, cierre/reapertura de cámara y rendimiento del S25 requieren una prueba en el teléfono. No se han inventado resultados de ese dispositivo.

## Mapa de funciones de la actividad

- `prepararControles`: construye los doce SeekBars y conecta botones, selector de vista y CPU/GPU.
- `activarCamara`, `onResume`, `onPause`, `onDestroy`: abren la cámara solo con permiso y en primer plano; la cierran al salir.
- `onCameraViewStarted` y `onCameraViewStopped`: crean/liberan la salida y reinician el procesador cuando sus callbacks ya se cerraron.
- `onCameraFrame`: toma valores consistentes, llama a JNI, agrega una muestra de la sesión correcta y devuelve RGBA a OpenCV.
- `actualizarRecursos`: mide FPS, CPU y RAM sin sustituirlos por valores supuestos.
- `iniciarMedicion` y `actualizarControles`: controlan calentamiento/registro y evitan cambiar las condiciones de una medición activa.
- `textoCsv`, `escribirCsv` y el contrato `guardarCsv`: toman el registro, piden destino y escriben una copia fuera del hilo de interfaz.
- `onSaveInstanceState`: conserva parámetros, modo, vista, cámara y CSV al recrearse la actividad.
- `vigilancia`: avisa si pasan ocho segundos sin fotogramas. Ofrece reinicio manual, sin reiniciar la cámara en un bucle oculto.

En C++, `parametrosDesdeControles` reconstruye Parametros y valida; `etapa` selecciona una matriz del pipeline; `prepararVista` adapta el color/comparación; `procesarCpu` y `procesarGpu` comparten la misma matemática. `comprobarGpu` consulta el dispositivo y el estado real de OpenCL. Las cuatro funciones JNI conectan diagnóstico, procesamiento, información GPU y reinicio con Kotlin.
