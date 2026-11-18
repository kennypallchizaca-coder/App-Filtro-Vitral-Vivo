# IntercicloVision — Android

Aplicación de la **Parte I** de Visión Artificial para Android: cámara, C++/OpenCV por JNI y **Vitral Vivo**, un vitral de triángulos irregulares con malla fija. No incluye Parte II.

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
- Siete deslizadores con los mismos valores/rangos del escritorio. El Gaussiano siempre es impar.
- CPU `Mat` y `UMat/OpenCL`, con indicación del modo realmente utilizado y respaldo CPU si la GPU no se puede usar.
- Tiempo, FPS procesados, CPU del proceso, RAM PSS y memoria disponible del sistema.
- Ventanas de medición CPU/OpenCL por separado, sin exportar archivos.

## Mediciones

Selecciona **CPU / Mat**, espera su primera muestra completa y luego cambia a **GPU / OpenCL** sin cambiar cámara, vista ni controles. La app ejecuta una ruta por vez, reinicia los búferes y conserva la última muestra completa de cada modo. Los datos no corresponden al mismo fotograma. El respaldo CPU no se anota como OpenCL. No se guardan mediciones en archivos.

Mat y UMat usan el mismo filtrado; OpenCL descarga color y bordes reducidos y sincroniza con `ocl::finish`. El dibujo de piezas es CPU e integra el tiempo total. FPS cuenta fotogramas reales; RAM PSS y CPU se muestrean alrededor de una vez por segundo. «—» significa que no hay muestra válida.

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

El repositorio de escritorio contiene `docs/ENTREGA_PARTE_I.md` y `docs/GUION_VIDEO_EN.md`. En el S25 Ultra se ejecutó la prueba instrumentada y se comprobó la cámara, el giro, la comparación izquierda/derecha y OpenCL en Adreno 830 con ambas muestras visibles. Falta un benchmark controlado y revisar todas las combinaciones de permisos y cámaras. No se usaron emuladores x86_64.

## Portal Vitral

**Portal Vitral** muestra la cámara real fuera del marco y el efecto dentro. **Vitral Vivo** aplica el efecto a toda la imagen. Ambos disponen de los mismos controles.
