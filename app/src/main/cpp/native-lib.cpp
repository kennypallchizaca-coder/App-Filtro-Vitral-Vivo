#include <jni.h>
#include <string>
#include <opencv2/core.hpp>
#include <opencv2/core/ocl.hpp>
#include "ProcesadorAndroid.hpp"
#include <memory>
#include <mutex>
#include <array>

namespace {
std::mutex acceso;
std::unique_ptr<ProcesadorAndroid> procesador;

void lanzar(JNIEnv* env, const char* clase, const char* mensaje) {
    if (!env->ExceptionCheck())
        env->ThrowNew(env->FindClass(clase), mensaje);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_interciclovision_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "OpenCV " + cv::getVersionString();

    if (cv::ocl::haveOpenCL()) {
        hello += "\nOpenCL disponible";
    } else {
        hello += "\nOpenCL no disponible";
    }
    return env->NewStringUTF(hello.c_str());
}

// Asistencia de IA: OpenAI Codex ajustó el puente JNI a los siete controles ASCII.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_interciclovision_MainActivity_procesarFrame(
        JNIEnv* env, jobject, jlong entrada, jlong salida, jintArray controles,
        jboolean gpu, jint vista, jboolean comparar) {
    try {
        std::array<jint, 7> datos;
        if (!entrada || !salida || !controles || env->GetArrayLength(controles) != datos.size())
            throw std::invalid_argument("Frame o controles invalidos");
        env->GetIntArrayRegion(controles, 0, datos.size(), datos.data());
        if (env->ExceptionCheck())
            return nullptr;
        std::vector<int> valores(datos.begin(), datos.end());
        std::lock_guard<std::mutex> bloqueo(acceso);
        if (!procesador)
            procesador = std::make_unique<ProcesadorAndroid>();
        auto t = procesador->procesar(*reinterpret_cast<cv::Mat*>(entrada),
                                     *reinterpret_cast<cv::Mat*>(salida), valores, gpu, vista, comparar);
        jdouble datosTiempo[]{t.cpuMs, t.gpuMs, t.cargaMs, t.filtroGpuMs,
                              t.descargaMs, t.gpuUsada ? 1.0 : 0.0};
        auto resultado = env->NewDoubleArray(6);
        if (resultado)
            env->SetDoubleArrayRegion(resultado, 0, 6, datosTiempo);
        return resultado;
    } catch (const std::invalid_argument& e) {
        lanzar(env, "java/lang/IllegalArgumentException", e.what());
    } catch (const std::exception& e) {
        lanzar(env, "java/lang/RuntimeException", e.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_interciclovision_MainActivity_informacionGpu(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> bloqueo(acceso);
    const std::string texto = (procesador ? procesador->informacion() : "GPU pendiente de comprobar").substr(0, 400);
    return env->NewStringUTF(texto.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_interciclovision_MainActivity_reiniciarProcesador(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> bloqueo(acceso);
    procesador.reset();
}
