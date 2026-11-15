#include <jni.h>
#include <string>
#include <opencv2/core.hpp>
#include <opencv2/core/ocl.hpp>

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