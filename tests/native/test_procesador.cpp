#ifdef NDEBUG
#undef NDEBUG
#endif
#include "ProcesadorAndroid.hpp"
#include <cassert>
#include <cmath>
#include <iostream>

using namespace cv;
using namespace std;

int main() {
    vector<int> valores{40, 5, 173, 30, 100, 9, 150};
    Parametros p = parametrosDesdeControles(valores);
    assert(p.tamGauss == 5 && p.sigma == 1.73 && p.pesoPropio == 0.3);
    assert(abs(sum(crearKernel(1))[0]) < 1e-6);

    Mat rgba(47, 63, CV_8UC4);
    randu(rgba, Scalar(0, 0, 0, 255), Scalar(256, 256, 256, 256));
    Mat original = rgba.clone(), salida;
    ProcesadorAndroid procesador;
    for (int vista = 0; vista <= 8; ++vista) {
        auto t = procesador.procesar(rgba, salida, valores, false, vista, false);
        assert(salida.size() == rgba.size() && salida.type() == CV_8UC4);
        assert(t.cpuMs > 0 && !t.gpuUsada && isnan(t.gpuMs));
        if (vista == 2)
            assert(norm(salida, original, NORM_INF) == 0);
        if (vista == 0) {
            Mat originalCompleto, bgr, final, finalCompleto;
            resize(original, originalCompleto, Size(31, 23), 0, 0, INTER_AREA);
            assert(norm(salida(Rect(0, 12, 31, 23)), originalCompleto, NORM_INF) == 0);
            Efecto<Mat> referenciaComparacion;
            cvtColor(rgba, bgr, COLOR_RGBA2BGR);
            referenciaComparacion.procesar(bgr, p);
            cvtColor(dibujarVitral(referenciaComparacion.cuadricula(), rgba.size()), final, COLOR_BGR2RGBA);
            resize(final, finalCompleto, Size(32, 24), 0, 0, INTER_AREA);
            assert(norm(salida(Rect(31, 11, 32, 24)), finalCompleto, NORM_INF) == 0);
        }
    }
    assert(norm(rgba, original, NORM_INF) == 0);

    Mat vertical(63, 47, CV_8UC4), comparacionVertical, miniatura;
    randu(vertical, Scalar(0, 0, 0, 255), Scalar(256, 256, 256, 256));
    procesador.procesar(vertical, comparacionVertical, valores, false, 0, false);
    resize(vertical, miniatura, Size(23, 31), 0, 0, INTER_AREA);
    if (norm(comparacionVertical(Rect(0, 16, 23, 31)), miniatura, NORM_INF) != 0) {
        cerr << "Portrait must place the complete original on the left\n";
        return 1;
    }
    Mat verticalBgr, verticalFinal;
    Efecto<Mat> referenciaVertical;
    cvtColor(vertical, verticalBgr, COLOR_RGBA2BGR);
    referenciaVertical.procesar(verticalBgr, p);
    cvtColor(dibujarVitral(referenciaVertical.cuadricula(), vertical.size()), verticalFinal, COLOR_BGR2RGBA);
    resize(verticalFinal, miniatura, Size(24, 32), 0, 0, INTER_AREA);
    assert(norm(comparacionVertical(Rect(23, 15, 24, 32)), miniatura, NORM_INF) == 0);

    cvtColor(rgba, original, COLOR_RGBA2BGR);
    Efecto<Mat> referencia;
    Mat esperado;
    referencia.procesar(original, p);
    cvtColor(dibujarVitral(referencia.cuadricula(), rgba.size()), esperado, COLOR_BGR2RGBA);
    procesador.procesar(rgba, salida, valores, false, 1, false);
    assert(norm(salida, esperado, NORM_INF) == 0);

    assert(norm(salida, rgba, NORM_INF) > 0);
    for (Size tamano : {Size(1, 1), Size(5, 7), Size(24, 24), Size(65, 49)}) {
        Mat pequena(tamano, CV_8UC4, Scalar(80, 130, 180, 255)), resultado;
        for (int vista : {0, 1, 2, 9}) {
            procesador.procesar(pequena, resultado, valores, false, vista, false);
            assert(resultado.size() == tamano && resultado.type() == CV_8UC4);
            if (vista == 2) assert(norm(resultado, pequena, NORM_INF) == 0);
        }
    }
    Mat antes = salida.clone();
    auto invalido = valores;
    invalido[1] = 4;
    bool rechazo = false;
    try { procesador.procesar(rgba, salida, invalido, false, 1, false); }
    catch (const invalid_argument&) { rechazo = true; }
    assert(rechazo && norm(salida, antes, NORM_INF) == 0);
    rechazo = false;
    try { procesador.procesar(Mat(), salida, valores, false, 1, false); }
    catch (const invalid_argument&) { rechazo = true; }
    assert(rechazo && norm(salida, antes, NORM_INF) == 0);
    rechazo = false;
    try { parametrosDesdeControles(vector<int>(12)); }
    catch (const invalid_argument&) { rechazo = true; }
    assert(rechazo);

    for (int celda : {5, 25}) {
        invalido = valores;
        invalido[5] = celda;
        rechazo = false;
        try { procesador.procesar(rgba, salida, invalido, false, 1, false); }
        catch (const invalid_argument&) { rechazo = true; }
        assert(rechazo && norm(salida, antes, NORM_INF) == 0);
    }
    auto cpuEnVivo = procesador.procesar(rgba, salida, valores, false, 1, true);
    assert(cpuEnVivo.cpuMs > 0 && !cpuEnVivo.gpuUsada);
    assert(norm(salida, esperado, NORM_INF) == 0);
    auto t = procesador.procesar(rgba, salida, valores, true, 1, true);
    assert(t.cpuMs > 0 && salida.size() == rgba.size());
    if (t.gpuUsada) {
        assert(t.gpuMs > 0 && t.cargaMs >= 0 && t.filtroGpuMs >= 0 && t.descargaMs >= 0);
        assert(norm(salida, esperado, NORM_L1) / salida.total() / 4 < 12);
        cout << "OpenCL GPU path checked on host\n";
    } else {
        assert(isnan(t.gpuMs));
        assert(norm(salida, esperado, NORM_INF) == 0);
        cout << "GPU unavailable: truthful CPU fallback checked\n";
    }
    ProcesadorAndroid soloOpenCL;
    auto aislado = soloOpenCL.procesar(rgba, salida, valores, true, 1, false);
    assert(aislado.gpuUsada == t.gpuUsada && salida.size() == rgba.size());
    if (aislado.gpuUsada) {
        assert(isnan(aislado.cpuMs) && aislado.gpuMs > 0);
        cout << "OpenCL-only: no Mat measurement\n";
    } else {
        assert(aislado.cpuMs > 0 && isnan(aislado.gpuMs));
        assert(norm(salida, esperado, NORM_INF) == 0);
    }

    for (const Mat& entrada : {rgba, vertical}) {
        Mat completa, portal, base = entrada.clone();
        procesador.procesar(entrada, completa, valores, false, 1, false);
        procesador.procesar(entrada, portal, valores, false, 9, false);
        Rect esquina(0, 0, 4, 4), centro(entrada.cols / 2 - 2, entrada.rows / 2 - 2, 4, 4);
        assert(portal.size() == entrada.size() && portal.type() == entrada.type());
        assert(norm(portal(esquina), entrada(esquina), NORM_INF) == 0);
        assert(norm(portal(centro), completa(centro), NORM_INF) == 0);
        assert(portal.at<Vec4b>(regionPortal(entrada.size()).tl()) == Vec4b(25, 25, 25, 255));
        assert(norm(entrada, base, NORM_INF) == 0);
    }
    auto gpuPortal = procesador.procesar(rgba, salida, valores, true, 9, true);
    assert(gpuPortal.cpuMs > 0 && gpuPortal.gpuUsada == t.gpuUsada);
    assert(norm(salida(Rect(0, 0, 4, 4)), rgba(Rect(0, 0, 4, 4)), NORM_INF) == 0);
    antes = salida.clone();
    rechazo = false;
    try { procesador.procesar(rgba, salida, valores, false, 10, false); }
    catch (const invalid_argument&) { rechazo = true; }
    assert(rechazo && norm(salida, antes, NORM_INF) == 0);
    cout << "Portal movil: interior de vitral y exterior real en ambas orientaciones\n";
}
