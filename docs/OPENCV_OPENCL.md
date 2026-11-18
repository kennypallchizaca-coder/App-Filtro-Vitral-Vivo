# OpenCV with OpenCL on Android

This library is used by the Android camera/filter application. See README.md and docs/CODIGO_ANDROID.md for the mobile UI, CPU/GPU modes and measurement workflow.

## Run in Android Studio

1. Open `C:\Users\kenny\OneDrive\Documents\App-movil-vision`.
2. Sync the Gradle project after changing the OpenCV dependency.
3. Select the connected Samsung S25 Ultra and the `app` configuration.
4. Click **Run**. Accept any installation prompt on the phone yourself.

The camera application starts in CPU mode and can select UMat/OpenCL. The native processor checks GPU device availability and actual OpenCL enablement on the processing thread, synchronizes work, and reports CPU fallback. Runtime availability alone is not a GPU benchmark.

## Local dependency

- Source: official OpenCV `5.0.0` tag.
- Java classes, resources, license notices and C++ headers: official Maven AAR `org.opencv:opencv:5.0.0.1`.
- Native library: locally rebuilt `libopencv_java5.so`, with `WITH_OPENCL=ON`.
- Target: `arm64-v8a`, Android API 26, NDK `28.2.13676358`, `c++_shared`.
- `libc++_shared.so` comes from the same NDK as the new native library.
- The same OpenCV library is stored in both the AAR's JNI and Prefab locations.
- This build targets the S25 Ultra's ARM64 architecture, not an x86 emulator.

The optional `libOpenCL.so` manifest declaration requests access to the vendor library on Android 12 and newer. The vendor driver is **not** bundled in the APK. Devices without it can still install the application.

## Rebuild the APK

In PowerShell:

```powershell
cd "C:\Users\kenny\OneDrive\Documents\App-movil-vision"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

Expected APK location:

`C:\Users\kenny\OneDrive\Documents\App-movil-vision\app\build\outputs\apk\debug\app-debug.apk`

## Packaging and checks

`tools/package_opencv_opencl.py` takes three paths: the official AAR, the rebuilt ARM64 OpenCV library, and the matching ARM64 C++ runtime. It preserves the official Java/Prefab interface and writes `app/libs/opencv-5.0.0-opencl-arm64.aar`. The original Maven cache is never modified.

```powershell
python .\tools\package_opencv_opencl.py --help
python .\tools\test_opencl_setup.py
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

The Python checks validate the local dependency, optional manifest declaration, OpenCL build flag, matching JNI/Prefab binaries, ARM64 architecture and 16 KB ELF segment alignment. They do not contact a phone.

Native source and build outputs are kept outside this application:

- `C:\Users\kenny\OpenCV-Android\opencv-5.0.0`
- `C:\Users\kenny\OpenCV-Android\build-arm64-opencl`

The app's existing JNI function, package name and CMake imported target are preserved.

## Reproduce the native build

Use the already downloaded OpenCV 5.0.0 source. From PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\kenny\AppData\Local\Android\Sdk"
$env:PYTHONDONTWRITEBYTECODE = "1"
$cmake = "$env:ANDROID_HOME\cmake\3.22.1\bin\cmake.exe"
$ndk = "$env:ANDROID_HOME\ndk\28.2.13676358"
$source = "C:\Users\kenny\OpenCV-Android\opencv-5.0.0"
$build = "C:\Users\kenny\OpenCV-Android\build-arm64-opencl"
& $cmake -S $source -B $build -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$env:ANDROID_HOME\cmake\3.22.1\bin\ninja.exe" `
  "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
  "-DANDROID_SDK=$env:ANDROID_HOME" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared `
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=17 -DBUILD_SHARED_LIBS=OFF `
  -DBUILD_FAT_JAVA_LIB=ON -DBUILD_JAVA=ON -DBUILD_opencv_java=ON `
  -DBUILD_ANDROID_PROJECTS=ON -DBUILD_ANDROID_EXAMPLES=OFF `
  -DBUILD_EXAMPLES=OFF -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_DOCS=OFF `
  -DBUILD_KOTLIN_EXTENSIONS=OFF -DWITH_OPENCL=ON -DWITH_TBB=OFF -DWITH_IPP=OFF `
  "-DBUILD_LIST=calib,core,dnn,features,flann,geometry,highgui,imgcodecs,imgproc,java,objdetect,photo,ptcloud,stereo,stitching,video,videoio" `
  "-DOPENCV_DOWNLOAD_PATH=C:\Users\kenny\OpenCV-Android\downloads" `
  "-DPYTHON3_EXECUTABLE=C:\Users\kenny\AppData\Local\Programs\Python\Python312\python.exe" `
  "-DPYTHON_EXECUTABLE=C:\Users\kenny\AppData\Local\Programs\Python\Python312\python.exe"
& $cmake --build $build --target opencv_java --parallel 4
```

`BUILD_ANDROID_PROJECTS=ON` is needed for Gradle/Java wrapper detection, even when building only the native library. The CMake target is `opencv_java`; its output filename is `libopencv_java5.so`.

Package the result from the application root:

```powershell
$official = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.opencv\opencv\5.0.0.1\*\*.aar" | Select-Object -First 1
$runtime = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so"
python .\tools\package_opencv_opencl.py $official.FullName "$build\jni\arm64-v8a\libopencv_java5.so" $runtime
python .\tools\test_opencl_setup.py
```

An optional KLEIDICV acceleration download failed during the verified native configure because of an SSL connection error. OpenCV used its normal implementations and the native build completed successfully. This does not enable or disable the vendor OpenCL runtime.
