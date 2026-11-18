# IntercicloVision — Android

Aplicación de la **Parte I** de Visión Artificial para Android: cámara, procesamiento C++/OpenCV mediante JNI y estética de cómic inspirada en Spider-Verse. La Parte II no está incluida.

## Ejecutar

1. Abre esta carpeta en Android Studio y sincroniza Gradle.
2. Selecciona el S25 Ultra y ejecuta la configuración `app`.
3. Pulsa **Permitir cámara** y acepta la autorización de Android.
4. Elige CPU/Mat o GPU/UMat, la vista y los parámetros.

APK de depuración: `app/build/outputs/apk/debug/app-debug.apk`. Arquitectura ARM64, Android 8/API 26 o superior. La compilación usa SDK 37, NDK 28.2.13676358 y OpenCV 5.0.0 con OpenCL habilitado. No solicita Internet ni acceso general al almacenamiento.

## Funciones

- Cámara frontal y trasera, con cierre al pasar a segundo plano.
- Original, efecto completo y comparación de ambas imágenes completas, sin deformar la imagen.
- Intermedios: gris, mediana, Gaussiano, Sobel combinado, kernel propio y máscara.
- Doce deslizadores con los mismos valores/rangos del escritorio. El Gaussiano siempre es impar.
- CPU `Mat` y `UMat/OpenCL`, con indicación del modo realmente utilizado y respaldo CPU si la GPU no se puede usar.
- Tiempo, FPS procesados, CPU del proceso, RAM PSS y memoria disponible del sistema.
- Comparación de tiempos CPU/GPU en vivo sobre el mismo fotograma, sin registro ni exportación de archivos.

## Mediciones

Los tiempos CPU y GPU se calculan sobre el mismo fotograma con los parámetros actuales. CPU/Mat y GPU/UMat eligen qué resultado mostrar; ambas rutas se cronometran para mantener la comparación visible. Esto añade carga respecto a ejecutar una sola ruta. No hay botón de grabar mediciones ni exportación CSV.

La GPU se sincroniza con `ocl::finish`; el tiempo incluye la subida/descarga y las conversiones de imagen de esa ruta. El paso final de copia al Mat de salida de JNI, la captura y el dibujo de la vista no están incluidos en ese cronómetro. Los FPS se calculan aparte con fotogramas entregados por unidad de tiempo. RAM y CPU se muestrean aproximadamente cada segundo; los campos sin muestra quedan vacíos, no se inventan ceros.

`UMat/OpenCL` no significa que todas las operaciones se ejecuten en GPU: T-API puede utilizar CPU para una operación no compatible. No se muestra un porcentaje GPU ni VRAM porque no se dispone de un contador Android portátil para ello. Un resultado GPU más lento sigue siendo un resultado válido; no se promete aceleración.

## Código y comprobaciones

- [Explicación del código y kernel](docs/CODIGO_ANDROID.md).
- [Compilación de la biblioteca OpenCL](docs/OPENCV_OPENCL.md).

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python -m unittest discover -s tools -p "test_*.py"
```

Las pruebas instrumentadas se compilan en el APK de pruebas; ejecutarlas sobre un teléfono es un paso separado. Antes de dar por validado el dispositivo hay que probar permiso denegado/concedido, frontal/trasera, rotación, segundo plano, diez vistas, CPU/GPU. La compilación no demuestra por sí sola el rendimiento ni el comportamiento de la cámara del S25.

VTK corresponde exclusivamente a escritorio. El informe y el vídeo-blog en inglés son entregables académicos globales separados del APK.

El informe con datos reales de escritorio y el guion en inglés se guardan en `docs/INFORME_PARTE_I.html` y `docs/GUION_VIDEO_EN.md` del repositorio de escritorio. El usuario confirmó funcionamiento en su S25 Ultra; esta revisión no volvió a medir ni a transferir archivos del teléfono. El APK es ARM64 y no se validó con los emuladores x86_64 instalados.

## Portal cómic

Selecciona **Portal cómic** para ver la cámara real fuera del marco y el filtro Spiderman dentro. El marco se dibuja con OpenCV; la app no requiere VTK ni una barra de transformación. **Spiderman** conserva el efecto en toda la imagen y los doce controles del filtro siguen disponibles.
