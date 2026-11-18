#pragma once

#include "Principal.hpp"
#include <opencv2/core/ocl.hpp>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <limits>
#include <string>

using namespace std;
using namespace cv;

inline Parametros parametrosDesdeControles(const vector<int>& v) {
    if (v.size() != 7)
        throw invalid_argument("Se requieren los 7 parametros");
    Parametros p;
    p.umbral = v[0];
    p.tamGauss = v[1];
    p.sigma = v[2] / 100.0;
    p.pesoPropio = v[3] / 100.0;
    p.diagonal = v[4] / 100.0;
    p.celda = v[5];
    p.contraste = v[6] / 100.0;
    p.validar();
    return p;
}

inline Rect regionPortal(Size tamano) {
    int ancho = tamano.width - tamano.width / 2;
    int alto = tamano.height - tamano.height / 4;
    return Rect((tamano.width - ancho) / 2, (tamano.height - alto) / 2, ancho, alto);
}

// Recupera la cámara fuera del marco y conserva el ASCII dentro.
inline void aplicarPortal(const Mat& original, Mat& resultado) {
    if (original.empty() || original.size() != resultado.size() || original.type() != resultado.type())
        throw invalid_argument("El portal requiere dos imagenes del mismo tamano y tipo");
    Mat fuera(original.size(), CV_8UC1, Scalar(255));
    fuera(regionPortal(original.size())).setTo(0);
    original.copyTo(resultado, fuera);
}

struct TiemposAndroid {
    double cpuMs = numeric_limits<double>::quiet_NaN();
    double gpuMs = numeric_limits<double>::quiet_NaN();
    double cargaMs = 0, filtroGpuMs = 0, descargaMs = 0;
    bool gpuUsada = false;
};

// Asistencia de IA: OpenAI Codex adaptó las vistas y los tiempos de Android al ASCII.
class ProcesadorAndroid {
    static constexpr int vistaPortal = 9;

public:
    // Procesa las rutas solicitadas y prepara la vista; JNI serializa cámara y reinicio.
    TiemposAndroid procesar(const Mat& rgba, Mat& salida, const vector<int>& valores,
                            bool usarGpu, int vista, bool comparar) {
        Parametros p = parametrosDesdeControles(valores);
        if (rgba.empty() || rgba.type() != CV_8UC4 || vista < 0 || vista > vistaPortal)
            throw invalid_argument("Se requiere RGBA 8UC4 y una vista valida");
        if (&rgba == &salida || (!salida.empty() && salida.data == rgba.data))
            throw invalid_argument("Entrada y salida deben ser matrices distintas");

        TiemposAndroid t;
        if (!usarGpu || comparar)
            t.cpuMs = procesarCpu(rgba, p, vista);
        bool gpuCorrecta = false;
        if ((usarGpu || comparar) && comprobarGpu()) {
            try {
                t.gpuMs = procesarGpu(rgba, p, vista, t);
                gpuCorrecta = true;
            } catch (const cv::Exception& e) {
                gpuDisponible = false;
                estadoGpu = string("GPU no utilizable; CPU activo: ") + e.what();
                t.gpuMs = numeric_limits<double>::quiet_NaN();
            }
        }
        t.gpuUsada = usarGpu && gpuCorrecta;
        if (!t.gpuUsada && isnan(t.cpuMs))
            t.cpuMs = procesarCpu(rgba, p, vista);
        (t.gpuUsada ? salidaGpu : salidaCpu).copyTo(salida);
        return t;
    }

    const string& informacion() const { return estadoGpu; }

private:
    using Reloj = chrono::steady_clock;
    Efecto<Mat> cpu;
    Efecto<UMat> gpu;
    Mat bgr, salidaCpu, salidaGpu;
    UMat entradaGpu;
    bool gpuComprobada = false, gpuDisponible = false;
    string estadoGpu = "GPU pendiente de comprobar";

    static double milisegundos(Reloj::time_point inicio) {
        return chrono::duration<double, milli>(Reloj::now() - inicio).count();
    }

    bool comprobarGpu() {
        if (gpuComprobada)
            return gpuDisponible;
        gpuComprobada = true;
        try {
            ocl::setUseOpenCL(true);
            if (ocl::haveOpenCL() && ocl::useOpenCL()) {
                const auto& dispositivo = ocl::Device::getDefault();
                gpuDisponible = dispositivo.available() &&
                                (dispositivo.type() & ocl::Device::TYPE_GPU);
                estadoGpu = gpuDisponible ? "OpenCL: " + dispositivo.name()
                                          : "No hay dispositivo GPU OpenCL; CPU activo";
            } else {
                estadoGpu = "OpenCL no utilizable; CPU activo";
            }
        } catch (const cv::Exception& e) {
            estadoGpu = string("OpenCL no utilizable; CPU activo: ") + e.what();
        }
        return gpuDisponible;
    }

    // Selecciona una etapa intermedia sin recalcular los filtros.
    template <class Imagen>
    static const Imagen& etapa(Efecto<Imagen>& efecto, int vista) {
        switch (vista) {
            case 3: return efecto.gris;
            case 4: return efecto.mediana;
            case 5: return efecto.imgGauss;
            case 6: return efecto.gradiente;
            case 7: return efecto.filtroPropioAbs;
            case 8: return efecto.binaria;
            default: throw invalid_argument("Se requiere una vista intermedia");
        }
    }

    // Compone la vista seleccionada sin deformar la comparación.
    static void prepararVista(const Mat& rgba, const Mat& imagen, Mat& salida, int vista) {
        if (vista == 2) {
            rgba.copyTo(salida);
            return;
        }
        if (vista != 0 || rgba.cols < 2 || rgba.rows < 2) {
            cvtColor(imagen, salida, imagen.channels() == 1 ? COLOR_GRAY2RGBA : COLOR_BGR2RGBA);
            if (vista == vistaPortal) {
                aplicarPortal(rgba, salida);
                Mat interior = salida(regionPortal(salida.size()));
                rectangle(interior, Rect(0, 0, interior.cols, interior.rows), Scalar(25, 25, 25, 255), 2);
            }
            return;
        }
        Mat efecto;
        cvtColor(imagen, efecto, COLOR_BGR2RGBA);
        salida.create(rgba.size(), CV_8UC4);
        salida.setTo(Scalar(247, 243, 232, 255));
        if (rgba.rows > rgba.cols) {
            int mitad = rgba.rows / 2;
            encajar(rgba, salida, Rect(0, 0, rgba.cols, mitad));
            encajar(efecto, salida, Rect(0, mitad, rgba.cols, rgba.rows - mitad));
        } else {
            int mitad = rgba.cols / 2;
            encajar(rgba, salida, Rect(0, 0, mitad, rgba.rows));
            encajar(efecto, salida, Rect(mitad, 0, rgba.cols - mitad, rgba.rows));
        }
    }

    // Encaja la imagen completa sin deformarla, como las composiciones de clase.
    static void encajar(const Mat& imagen, Mat& lienzo, const Rect& panel) {
        double escala = min(double(panel.width) / imagen.cols, double(panel.height) / imagen.rows);
        Size tamano(max(1, cvRound(imagen.cols * escala)), max(1, cvRound(imagen.rows * escala)));
        Mat reducida;
        resize(imagen, reducida, tamano, 0, 0, INTER_AREA);
        Rect posicion(panel.x + (panel.width - tamano.width) / 2,
                      panel.y + (panel.height - tamano.height) / 2, tamano.width, tamano.height);
        reducida.copyTo(lienzo(posicion));
    }

    double procesarCpu(const Mat& rgba, const Parametros& p, int vista) {
        ocl::setUseOpenCL(false);
        auto inicio = Reloj::now();
        cvtColor(rgba, bgr, COLOR_RGBA2BGR);
        cpu.procesar(bgr, p);
        Mat imagen;
        if (vista >= 3 && vista <= 8) imagen = etapa(cpu, vista);
        else if (vista != 2) imagen = dibujarAscii(cpu.cuadricula(), rgba.size());
        prepararVista(rgba, imagen, salidaCpu, vista);
        return milisegundos(inicio);
    }

    double procesarGpu(const Mat& rgba, const Parametros& p, int vista, TiemposAndroid& t) {
        ocl::setUseOpenCL(true);
        if (!ocl::useOpenCL())
            CV_Error(Error::StsError, "OpenCL se desactivo durante el procesamiento");
        auto inicio = Reloj::now();
        cvtColor(rgba, bgr, COLOR_RGBA2BGR);
        auto carga = Reloj::now();
        bgr.copyTo(entradaGpu);
        ocl::finish();
        t.cargaMs = milisegundos(carga);
        auto filtro = Reloj::now();
        gpu.procesar(entradaGpu, p);
        ocl::finish();
        t.filtroGpuMs = milisegundos(filtro);
        auto descarga = Reloj::now();
        Mat imagen;
        CuadriculaAscii cuadricula;
        if (vista >= 3 && vista <= 8) etapa(gpu, vista).copyTo(imagen);
        else if (vista != 2) cuadricula = gpu.cuadricula();
        ocl::finish();
        t.descargaMs = milisegundos(descarga);
        // La descarga se mide aparte; el dibujo CPU pertenece al total de la ruta.
        if (vista == 0 || vista == 1 || vista == vistaPortal)
            imagen = dibujarAscii(cuadricula, rgba.size());
        prepararVista(rgba, imagen, salidaGpu, vista);
        return milisegundos(inicio);
    }
};
