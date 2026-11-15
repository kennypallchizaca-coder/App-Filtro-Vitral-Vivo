#include "ProcesadorAndroid.hpp"
#include <cassert>
#include <cmath>
#include <iostream>

using namespace cv;
using namespace std;

int main() {
    vector<int> valores{40, 5, 173, 30, 100, 8, 1, 125, 100, 115, 1, 150};
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
        if (vista == 0)
            assert(norm(salida.colRange(0, rgba.cols / 2 - 1),
                        original.colRange(0, rgba.cols / 2 - 1), NORM_INF) == 0);
    }
    assert(norm(rgba, original, NORM_INF) == 0);

    cvtColor(rgba, original, COLOR_RGBA2BGR);
    Efecto<Mat> referencia;
    Mat esperado;
    cvtColor(referencia.procesar(original, p), esperado, COLOR_BGR2RGBA);
    procesador.procesar(rgba, salida, valores, false, 1, false);
    assert(norm(salida, esperado, NORM_INF) == 0);

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
    try { parametrosDesdeControles(vector<int>(11)); }
    catch (const invalid_argument&) { rechazo = true; }
    assert(rechazo);

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
    cout << "Native Android adapter tests passed\n";
}
