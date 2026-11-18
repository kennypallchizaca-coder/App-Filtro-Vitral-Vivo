# Código de la aplicación Android

**Qué hicimos:** conectamos la cámara y los controles de Android al mismo filtro C++ del escritorio. JNI hace de puente; C++ dibuja la comparación y Kotlin muestra las mediciones de cada modo sin mezclarlas. En Android Studio, abre los archivos indicados en el [recorrido por líneas](#recorrido-por-líneas-en-android-studio) y sigue un fotograma desde la cámara hasta la pantalla.

## Recorrido de un fotograma

1. `MainActivity.kt` prepara permiso, cámara OpenCV y controles.
2. La cámara entrega un Mat RGBA prestado; no se libera ni altera esa entrada.
3. La actividad toma los parámetros y la vista actuales y llama a JNI.
4. `native-lib.cpp` comprueba que las referencias no sean nulas y que lleguen siete controles. Serializa procesamiento/reinicio y traduce excepciones. El procesador nativo valida el tipo de imagen, los rangos y la vista.
5. `ProcesadorAndroid.hpp` convierte RGBA a BGR y utiliza el mismo `Efecto<Mat>` / `Efecto<UMat>` del escritorio.
6. La aplicación ejecuta el modo seleccionado, CPU/Mat o GPU/OpenCL. Al cambiar de modo arranca una ventana nueva y conserva la última muestra completa de cada ruta. El respaldo CPU se indica y no genera una muestra OpenCL.
7. Se vuelve a RGBA y se conserva el tamaño de entrada requerido por el Bitmap.
8. La comparación encaja el original completo a la izquierda y el efecto a la derecha, en ambas orientaciones. No estira ni recorta las imágenes. En vertical pueden quedar espacios libres arriba y abajo.
9. Los textos de tiempos, FPS y recursos se actualizan cada medio segundo, sin guardar archivos. Están en el panel de controles para que sus cambios de altura no redimensionen la superficie de cámara.

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

`ParametrosMoviles.kt` define los siete controles en el orden en que llegan al código nativo: umbral, tamaño Gaussiano, sigma, peso del filtro propio, diagonal del kernel, escala del vitral y contraste CLAHE. Los decimales viajan como enteros escalados por 100. El tamaño Gaussiano es impar entre 3 y 51. La escala va de 6 a 24 y el lado del bloque base mide cerca de tres veces ese valor.

Los valores iniciales son `[40, 5, 173, 30, 100, 9, 150]`. Cada cambio publica una copia nueva del arreglo; JNI y C++ validan tamaño y rangos antes de modificar la salida. Un estado guardado antiguo de doce valores se descarta por incompatible y se conservan los valores iniciales.

## Mediciones en vivo

`Mediciones.kt` completa ventanas de al menos un segundo y calcula el tiempo medio por fotograma, FPS y porcentaje de CPU. La actividad añade PSS y RAM libre al cerrar cada ventana. Las dos últimas muestras completas (una por modo) solo viven en memoria; no hay exportación CSV.

- CPU: Mat con OpenCL desactivado.
- GPU: preprocesamiento UMat con un dispositivo OpenCL comprobado; dibujo del vitral en CPU. `ocl::finish` espera el trabajo de la cola antes de detener cada cronómetro. Elegir esta ruta no garantiza que todas las operaciones de OpenCV se ejecuten en GPU.
- El tiempo total incluye conversiones, dibujo y preparación de la vista; en OpenCL también las transferencias. No incluye captura, visualización de cámara ni la última copia JNI.
- FPS: fotogramas entregados por tiempo monotónico, no `1000/ms`.
- RAM del proceso: PSS convertido de KiB a MiB. No equivale a VRAM.
- RAM libre: ActivityManager, bytes convertidos a MiB.
- CPU: tiempo CPU dividido por tiempo de pared y procesadores lógicos, normalizado a 0..100%.
- Recursos: muestreo aproximadamente cada segundo. Antes de una muestra válida se muestra una raya.

La app ejecuta una ruta por vez. Al cambiar de modo reinicia procesador y ventana; una muestra completa queda disponible por modo. El respaldo CPU nunca se anota como OpenCL. PSS es memoria del proceso, no VRAM.

## Permisos y ciclo de vida

Sin permiso se explica el acceso necesario. Tras denegación permanente se puede abrir Ajustes. Al reanudar se verifica el permiso; al pausar se cierra la cámara y se esperan sus callbacks antes de liberar las matrices. No se comparte una instancia mutable de Efecto entre procesamientos paralelos.

Al recrear la actividad, el estado guardado restaura parámetros, modo, vista e índice de cámara; la captura se vuelve a abrir. La tarea de interfaz recibe datos de medición, no un Mat prestado. Un aviso tras ocho segundos sin fotogramas ofrece reinicio manual, sin bucles de reinicio ocultos.

## Pruebas

La [prueba nativa de PC](../tests/native/test_procesador.cpp) verifica diez vistas, composición horizontal y vertical con matrices sintéticas, salidas RGBA, coincidencia con el filtro de referencia y rechazo de entradas inválidas. No abre la cámara. También verifica tiempos y selección de CPU, GPU o respaldo.

Las pruebas JVM comprueban parámetros, porcentaje CPU y descarte de ventanas parciales. Python comprueba controles, permisos y empaquetado. La [prueba instrumentada](../app/src/androidTest/java/com/example/interciclovision/NativeEffectInstrumentedTest.kt#L15-L50) verifica JNI con una matriz sintética de 64×48; no comprueba la rotación ni la cámara. Se ejecuta en un dispositivo Android compatible con el APK ARM64; compilarla no la ejecuta.

## Portal Vitral

`prepararVista` convierte a RGBA. **Portal Vitral** devuelve el original fuera de un rectángulo mediante una máscara y marca el marco con `rectangle`.

La vista original conserva el procesamiento cronometrado, pero no descarga ni dibuja el vitral. Comparación, Vitral Vivo y Portal Vitral sí dibujan la malla. Las intermedias muestran la etapa elegida.

## Recorrido por líneas en Android Studio

Los números son del archivo fuente. Usa **Ctrl+G** en el editor para saltar a una línea. Las líneas que forman una sola operación se explican juntas; las importaciones y las llaves no cambian el recorrido del fotograma.

### 1. `MainActivity.kt`: prepara la cámara y entrega el fotograma

Abre [`MainActivity.kt`](../app/src/main/java/com/example/interciclovision/MainActivity.kt#L69-L104).

| Líneas | Qué hace y por qué |
|---|---|
| 33–67 | La actividad escucha los eventos de cámara de OpenCV, guarda estado y registra el resultado del permiso. El vigilante detecta si dejan de llegar fotogramas; muestra un aviso en lugar de reiniciar sola. |
| 69–86 | Inicializa OpenCV, crea las referencias al XML con View Binding y restaura controles, modo, vista e índice de cámara cuando hay estado guardado válido. |
| 88–104 | Adapta el espacio a la orientación, conecta el listener a `JavaCamera2View` y solicita una captura de hasta 640×480. Es un límite pedido a OpenCV, no una resolución garantizada. |
| 107–121 | Los botones cambian el modo solicitado y el selector cambia la vista. La vista 0 es la comparación visual; no selecciona dos rutas de procesamiento. |
| 122–149 | Por cada control crea un nombre y un deslizador. El evento calcula su valor, copia el arreglo anterior, cambia una posición y publica la copia. No modifica el arreglo que el hilo de cámara podría estar usando. |
| 150–179 | El botón de permiso solicita acceso, abre Ajustes o reinicia la cámara según el estado. Los otros botones alternan cámara o restablecen parámetros. |
| 181–218 | Antes de habilitar cámara exige permiso y actividad reanudada. Al pausar o destruir quita el vigilante y deshabilita la vista. |
| 220–239 | Cuando la cámara arranca, crea el `Mat` propio de salida y reinicia mediciones y procesador. Al detenerse libera esa salida, no la imagen prestada por OpenCV. |
| 341–357 | Guarda los cuatro valores de estado y declara las funciones `external` que ofrece la biblioteca JNI. |

Ahora ve a [`onCameraFrame`, líneas 241–312](../app/src/main/java/com/example/interciclovision/MainActivity.kt#L241-L312). Este es el bloque que conviene explicar en la defensa:

| Línea | Qué hace y por qué |
|---|---|
| 242 | Obtiene el `Mat` RGBA prestado de la cámara. No lo libera y no lo usa como destino. |
| 243–245 | Registra que llegó un fotograma. Si no hay salida preparada, la actividad está pausada o hubo un fallo, devuelve el original. |
| 247–251 | Lee referencias locales de los parámetros, la vista y el modo, y detecta qué cambió respecto de lo que se estaba midiendo. |
| 252–256 | Un cambio de modo libera los búferes nativos anteriores, pero no borra la última muestra completa del otro modo. |
| 257–265 | Un cambio de vista o de parámetros sí borra las dos muestras: ya no representan la misma configuración. Ambos tipos de cambio reinician la ventana parcial. |
| 266–267 | Pasa las direcciones de los dos `Mat`, siete valores, modo y vista a C++. El último `false` pide ejecutar solo un modo; puedes mantener «Comparación completa» con ese `false`. |
| 268 | Si los controles cambiaron mientras C++ trabajaba, devuelve la imagen, pero no registra sus tiempos como si fueran de la selección nueva. |
| 269–278 | Lee `tiempos[5]` para saber si se utilizó la salida GPU. Solo guarda una muestra si el modo usado coincide con el pedido. Si se pidió GPU y hubo respaldo CPU, borra la muestra GPU y reinicia la ventana sin atribuir ese fotograma a OpenCL. |
| 279–299 | Aproximadamente cada 500 ms consulta el diagnóstico y, en el hilo de interfaz, actualiza las dos filas y el estado de GPU. Comprueba otra vez que la selección siga siendo la misma. |
| 301–310 | Devuelve a OpenCV el `Mat` de salida. Si hay una excepción de ejecución, marca el fallo, muestra el error, deshabilita la cámara y devuelve el original. |

### 2. `native-lib.cpp`: el puente entre Kotlin y C++

Abre [`native-lib.cpp`, líneas 35–64](../app/src/main/cpp/native-lib.cpp#L35-L64).

| Líneas | Qué hace y por qué |
|---|---|
| 35–38 | JNI expone la función que Kotlin declaró como `external`. Los dos `jlong` son direcciones de matrices nativas, no listas de píxeles. |
| 40–45 | Exige direcciones distintas de cero, un arreglo no nulo y siete elementos. Copia los controles desde Java sin copiar el fotograma. Si la JVM señala un error, no continúa. |
| 46–51 | Prepara el vector C++, bloquea el acceso con un mutex, crea el procesador si no existe y llama a `procesar` con las matrices. Las direcciones deben provenir de objetos `Mat` válidos. |
| 52–57 | Coloca en un arreglo CPU total, GPU total, carga, filtrado GPU, descarga y la bandera GPU. La imagen ya se escribió en el `Mat` de salida; no viaja en ese arreglo. |
| 58–63 | Traduce parámetros inválidos a `IllegalArgumentException` y otros fallos C++ a `RuntimeException` para que Kotlin pueda reaccionar. |
| 66–77 | Usa el mismo bloqueo para leer el diagnóstico y destruir el procesador al reiniciar; no cambia sus búferes a mitad de una llamada. |

### 3. `ProcesadorAndroid.hpp`: adapta el filtro y compone las vistas

Abre [`ProcesadorAndroid.hpp`](../app/src/main/cpp/ProcesadorAndroid.hpp#L57-L83). También despliega [`Principal.hpp`](../app/src/main/cpp/Principal.hpp#L128-L186): es el núcleo compartido con escritorio.

| Líneas de `ProcesadorAndroid.hpp` | Qué hace y por qué |
|---|---|
| 14–27 | Convierte los siete enteros a parámetros del filtro. Por ejemplo, en la línea 20 el sigma `173` pasa a `1.73`. Valida los rangos antes de procesar. |
| 57–63 | Exige una entrada RGBA de 8 bits, una vista de 0 a 9 y una salida distinta de la entrada. |
| 65–83 | Ejecuta la ruta pedida. Si OpenCL no se puede utilizar, procesa con CPU e informa que la salida no vino de GPU. La línea 82 copia el resultado a la matriz de Kotlin después de cerrar el cronómetro de la ruta. |
| 101–119 | Comprueba que OpenCL pueda activarse y que el dispositivo esté disponible y sea de tipo GPU. Guarda el resultado para esa instancia. |
| 124–133 | Selecciona una etapa ya calculada. «Gris» es el gris después de mediana y CLAHE; «Mediana» es la salida anterior a CLAHE; «Sobel combinado» ya mezcla Sobel y el kernel propio. |
| 138–150 | «Original» copia RGBA sin dibujar malla. Otras vistas convierten gris o BGR a RGBA. En «Portal Vitral» se recupera el original fuera del rectángulo con una máscara (29–42) y se dibuja el marco. |
| 151–157 | En «Comparación completa» prepara una salida RGBA del tamaño original. El original va desde x=0 y el efecto desde x=ancho/2. No son dos modos de procesamiento. |
| 161–168 | `encajar` elige la menor escala que permite que la imagen entre completa, la reduce con `INTER_AREA`, la centra y la copia. El espacio libre en vertical no significa que se recortó la cámara. |
| 171–180 | CPU desactiva OpenCL, convierte RGBA a BGR, ejecuta el núcleo con `Mat`, elige etapa o dibuja el vitral, y compone la salida. |
| 183–208 | GPU convierte a BGR, carga a `UMat`, ejecuta y sincroniza el núcleo. Según la vista, descarga una etapa entera o solo los mapas reducidos. Dibuja las piezas en CPU y compone la salida RGBA. |

Dentro de `Principal.hpp`, sigue [145–153](../app/src/main/cpp/Principal.hpp#L145-L153) para gris, mediana, CLAHE y reducción de los dos mapas. En [174–186](../app/src/main/cpp/Principal.hpp#L174-L186), el Gaussiano suaviza; Sobel calcula cambios en x e y; `filter2D` aplica el kernel de suma cero; `convertScaleAbs` escala la respuesta propia por 12. La línea 184 mezcla las dos respuestas y las líneas 185–186 dejan una máscara limpia. En [81–103](../app/src/main/cpp/Principal.hpp#L81-L103), el relleno toma color del original y el detalle solo aumenta el contraste de las subdivisiones. No mueve la geometría de un fotograma a otro.

### 4. Controles, medición y disposición

| Archivo y líneas | Qué hace y por qué |
|---|---|
| [`ParametrosMoviles.kt:5–14`](../app/src/main/java/com/example/interciclovision/ParametrosMoviles.kt#L5-L14) | Un deslizador entrega una posición, no el valor directo: `mínimo + posición × paso`. Para el tamaño Gaussiano, posición 1 da `3 + 1×2 = 5`. |
| [`ParametrosMoviles.kt:17–34`](../app/src/main/java/com/example/interciclovision/ParametrosMoviles.kt#L17-L34) | Define rangos y valores iniciales. `validar` comprueba longitud, rango y que cada valor coincida con su paso. |
| [`Mediciones.kt:14–31`](../app/src/main/java/com/example/interciclovision/Mediciones.kt#L14-L31) | Descarta ventanas parciales al reiniciar. El primer fotograma fija la referencia de tiempo y CPU; los posteriores suman fotogramas y milisegundos durante al menos un segundo. |
| [`Mediciones.kt:32–45`](../app/src/main/java/com/example/interciclovision/Mediciones.kt#L32-L45) | Divide tiempo total entre fotogramas para la media; divide fotogramas entre segundos para FPS. Normaliza el tiempo CPU del proceso por tiempo de pared y número de núcleos lógicos. |
| [`MainActivity.kt:314–329`](../app/src/main/java/com/example/interciclovision/MainActivity.kt#L314-L329) | Solo al cerrar una ventana añade RAM PSS y RAM disponible, en MiB. Un dato ausente aparece como «—», no como cero. |
| [`activity_main.xml:10–51`](../app/src/main/res/layout/activity_main.xml#L10-L51) | Define el visor de cámara separado del panel desplazable. Ubica métricas en el panel: cambiar su altura ya no redimensiona la cámara ni reinicia sus muestras. |
| [`activity_main.xml:52–72`](../app/src/main/res/layout/activity_main.xml#L52-L72) | Declara selector y botones; Kotlin añade los deslizadores al contenedor reservado. |
| [`AndroidManifest.xml:5–8`](../app/src/main/AndroidManifest.xml#L5-L8) y [19–29](../app/src/main/AndroidManifest.xml#L19-L29) | Declara el permiso y los requisitos de cámara, OpenCL opcional y la actividad que abre Android. Declarar el permiso no sustituye pedirlo en ejecución. |
| [`app/build.gradle.kts:19–29`](../app/build.gradle.kts#L19-L29), [44–54 y 67](../app/build.gradle.kts#L44-L67) | Configura ARM64, CMake y C++17; View Binding conecta Kotlin con el XML y el AAR contiene OpenCV. [El CMake nativo](../app/src/main/cpp/CMakeLists.txt#L1-L11) compila `native-lib.cpp` y enlaza OpenCV. Para reconstruir el AAR, usa [OPENCV_OPENCL.md](OPENCV_OPENCL.md). |

**Para practicar:** parte de `MainActivity.kt:266`. Localiza dónde C++ convierte a BGR, dónde se mezclan Sobel y la respuesta del kernel para construir la máscara, y dónde se colocan las dos imágenes. Luego explica por qué puedes ver dos filas de mediciones aunque cada fotograma solo ejecute un modo.
