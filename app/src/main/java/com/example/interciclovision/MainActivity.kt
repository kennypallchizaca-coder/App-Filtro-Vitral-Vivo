package com.example.interciclovision

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.example.interciclovision.databinding.ActivityMainBinding
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.util.Locale

class MainActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {
    private lateinit var binding: ActivityMainBinding
    @Volatile private var valores = ParametrosMoviles.iniciales()
    private class SeleccionModo(val gpu: Boolean)
    @Volatile private var modoActual = SeleccionModo(false)
    @Volatile private var vista = 0
    @Volatile private var reanudada = false
    @Volatile private var falloFrame = false
    @Volatile private var ultimoFrame = 0L
    private var indiceCamara = CameraBridgeViewBase.CAMERA_ID_FRONT
    private var salida: Mat? = null
    private val sliders = mutableListOf<SeekBar>()
    private val ventanaMedicion = VentanaMedicion()
    private val muestras = arrayOfNulls<RecursosMedidos>(2)
    private var modoMedido: SeleccionModo? = null
    private var parametrosMedidos: IntArray? = null
    private var vistaMedida = -1
    private var ultimaUi = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val permisoCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) activarCamara() else {
            binding.status.setText(R.string.camera_denied)
            binding.emptyPreview.setText(R.string.camera_permission_message)
            actualizarControles()
        }
    }
    private val vigilancia = object : Runnable {
        override fun run() {
            if (!reanudada || !::binding.isInitialized) return
            if (tienePermiso() && !falloFrame && SystemClock.elapsedRealtime() - ultimoFrame > 8000)
                binding.status.setText(R.string.camera_timeout)
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) {
            setContentView(TextView(this).apply { setText(R.string.opencv_error) })
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightNavigationBars = true
        savedInstanceState?.getIntArray("valores")?.let {
            if (runCatching { ParametrosMoviles.validar(it) }.isSuccess) valores = it.copyOf()
        }
        modoActual = SeleccionModo(savedInstanceState?.getBoolean("gpu") ?: false)
        vista = (savedInstanceState?.getInt("vista") ?: 0)
            .coerceIn(0, resources.getStringArray(R.array.views).lastIndex)
        indiceCamara = savedInstanceState?.getInt("camara") ?: if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        ) CameraBridgeViewBase.CAMERA_ID_FRONT else CameraBridgeViewBase.CAMERA_ID_BACK

        val margen = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(margen + barras.left, margen + barras.top, margen + barras.right, margen + barras.bottom)
            insets
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.body.orientation = LinearLayout.HORIZONTAL
            binding.previewPanel.layoutParams = LinearLayout.LayoutParams(0, -1, 1.35f)
            binding.controlScroll.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        binding.cameraView.setCameraIndex(indiceCamara)
        binding.cameraView.setMaxFrameSize(640, 480)
        binding.cameraView.setCvCameraViewListener(this)
        binding.status.text = stringFromJNI()
        prepararControles()
        actualizarControles()
    }

    private fun prepararControles() {
        binding.backend.check(if (modoActual.gpu) R.id.gpu_button else R.id.cpu_button)
        binding.backend.setOnCheckedChangeListener { _, checked ->
            modoActual = SeleccionModo(checked == R.id.gpu_button)
            binding.metrics.setText(R.string.waiting_frames)
        }
        binding.viewSelector.setSelection(vista)
        binding.viewSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                vista = position
                binding.viewCaption.text = if (vista == 0) getString(R.string.comparison_caption)
                    else resources.getStringArray(R.array.views)[vista]
            }
        }
        ParametrosMoviles.controles.forEachIndexed { i, control ->
            val etiqueta = TextView(this).apply {
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.tinta))
                text = getString(R.string.control_value, control.nombre, control.mostrar(valores[i]))
            }
            val slider = SeekBar(this).apply {
                id = View.generateViewId()
                max = control.maximoProgreso
                progress = (valores[i] - control.minimo) / control.paso
                layoutParams = LinearLayout.LayoutParams(-1, (48 * resources.displayMetrics.density).toInt())
            }
            etiqueta.labelFor = slider.id
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    // Publicar un arreglo nuevo evita que la cámara lea parámetros a medio actualizar.
                    val nuevos = valores.copyOf()
                    nuevos[i] = control.desdeProgreso(progress)
                    valores = nuevos
                    etiqueta.text = getString(R.string.control_value, control.nombre, control.mostrar(nuevos[i]))
                }
            })
            binding.slidersContainer.addView(etiqueta)
            binding.slidersContainer.addView(slider)
            sliders.add(slider)
        }
        binding.permissionButton.setOnClickListener {
            if (tienePermiso()) {
                binding.cameraView.disableView()
                activarCamara()
            } else {
                val yaSolicitado = getPreferences(MODE_PRIVATE).getBoolean("permisoSolicitado", false)
                if (yaSolicitado && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)))
                } else {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("permisoSolicitado", true).apply()
                    permisoCamara.launch(Manifest.permission.CAMERA)
                }
            }
        }
        binding.cameraButton.setOnClickListener {
            binding.cameraView.disableView()
            indiceCamara = if (indiceCamara == CameraBridgeViewBase.CAMERA_ID_FRONT)
                CameraBridgeViewBase.CAMERA_ID_BACK else CameraBridgeViewBase.CAMERA_ID_FRONT
            binding.cameraView.setCameraIndex(indiceCamara)
            activarCamara()
        }
        binding.resetButton.setOnClickListener {
            valores = ParametrosMoviles.iniciales()
            sliders.forEachIndexed { i, slider ->
                val control = ParametrosMoviles.controles[i]
                slider.progress = (valores[i] - control.minimo) / control.paso
            }
        }
    }

    private fun tienePermiso() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    // Asistencia de IA: OpenAI Codex corrigió el estado de espera al volver a abrir la cámara.
    private fun activarCamara() {
        if (!::binding.isInitialized || !reanudada || !tienePermiso()) return
        modoActual = SeleccionModo(modoActual.gpu)
        binding.metrics.setText(R.string.waiting_frames)
        falloFrame = false
        ultimoFrame = SystemClock.elapsedRealtime()
        binding.emptyPreview.setText(R.string.camera_wait)
        binding.emptyPreview.visibility = View.VISIBLE
        binding.cameraView.visibility = View.VISIBLE
        binding.cameraView.setCameraPermissionGranted()
        binding.cameraView.enableView()
        handler.removeCallbacks(vigilancia)
        handler.postDelayed(vigilancia, 2000)
        actualizarControles()
    }

    override fun onResume() {
        super.onResume()
        reanudada = true
        activarCamara()
    }

    override fun onPause() {
        reanudada = false
        handler.removeCallbacks(vigilancia)
        if (::binding.isInitialized) binding.cameraView.disableView()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(vigilancia)
        if (::binding.isInitialized) binding.cameraView.disableView()
        super.onDestroy()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        salida = Mat()
        reiniciarProcesador()
        ventanaMedicion.reiniciar()
        muestras.fill(null)
        modoMedido = modoActual
        parametrosMedidos = null
        ultimaUi = 0L
        runOnUiThread {
            binding.emptyPreview.visibility = View.GONE
            actualizarControles()
        }
    }

    override fun onCameraViewStopped() {
        salida?.release()
        salida = null
        reiniciarProcesador()
        runOnUiThread { if (!isDestroyed) actualizarControles() }
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        val rgba = inputFrame.rgba()
        ultimoFrame = SystemClock.elapsedRealtime()
        val destino = salida ?: return rgba
        if (!reanudada || falloFrame) return rgba
        try {
            val parametros = valores
            val vistaActual = vista
            val modo = modoActual
            val cambioModo = modo !== modoMedido
            val cambioVista = parametros !== parametrosMedidos || vistaActual != vistaMedida
            if (cambioModo) {
                // Asistencia de IA (OpenAI Codex): se liberan búferes del modo anterior antes de medir PSS.
                reiniciarProcesador()
                modoMedido = modo
            }
            if (cambioVista) {
                muestras.fill(null)
                parametrosMedidos = parametros
                vistaMedida = vistaActual
            }
            if (cambioModo || cambioVista) {
                ventanaMedicion.reiniciar()
                ultimaUi = 0L
            }
            val tiempos = procesarFrame(rgba.nativeObjAddr, destino.nativeObjAddr,
                parametros, modo.gpu, vistaActual, false)
            if (modo !== modoActual || parametros !== valores || vistaActual != vista) return destino
            val gpuUsada = tiempos[5] == 1.0
            val ahora = SystemClock.elapsedRealtime()
            if (gpuUsada == modo.gpu) {
                val indice = if (gpuUsada) 1 else 0
                actualizarRecursos(ahora, tiempos[indice])?.let { muestras[indice] = it }
            } else {
                // El fotograma de respaldo no representa una muestra OpenCL.
                ventanaMedicion.reiniciar()
                muestras[1] = null
            }
            if (ahora - ultimaUi >= 500) {
                ultimaUi = ahora
                // Asistencia de IA: OpenAI Codex limitó esta consulta JNI al ritmo de la interfaz.
                val dispositivo = informacionGpu()
                val cpu = muestras[0]
                val gpu = muestras[1]
                val comparacionVertical = rgba.rows() > rgba.cols()
                runOnUiThread {
                    if (!isDestroyed && reanudada && modo === modoActual &&
                        parametros === valores && vistaActual == vista) {
                        if (vistaActual == 0) binding.viewCaption.setText(
                            if (comparacionVertical) R.string.comparison_vertical else R.string.comparison_horizontal)
                        binding.metrics.text = getString(R.string.live_metrics,
                            describirMedicion(R.string.cpu_mode, cpu), describirMedicion(R.string.gpu_mode, gpu))
                        val actual = when {
                            gpuUsada -> R.string.gpu_mode
                            modo.gpu -> R.string.cpu_fallback
                            else -> R.string.cpu_mode
                        }
                        binding.status.text = getString(R.string.live_backend,
                            getString(actual), dispositivo.take(220))
                        actualizarControles()
                    }
                }
            }
            return destino
        } catch (e: RuntimeException) {
            falloFrame = true
            runOnUiThread {
                if (!isDestroyed) {
                    binding.cameraView.disableView()
                    binding.status.text = getString(R.string.camera_error, e.message?.take(220))
                }
            }
            return rgba
        }
    }

    private fun actualizarRecursos(ahora: Long, procesoMs: Double): RecursosMedidos? {
        val muestra = ventanaMedicion.agregar(ahora, procesoMs,
            Runtime.getRuntime().availableProcessors(), Process::getElapsedCpuTime) ?: return null
        val memoria = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoria)
        return muestra.copy(ramPssMiB = Debug.getPss() / 1024.0,
            ramLibreMiB = memoria.availMem / (1024.0 * 1024.0))
    }

    private fun describirMedicion(modo: Int, muestra: RecursosMedidos?): String {
        val r = muestra ?: RecursosMedidos()
        return getString(R.string.mode_metrics, getString(modo), numero(r.procesoMs), numero(r.fps),
            numero(r.ramPssMiB), numero(r.cpuPorcentaje), numero(r.ramLibreMiB))
    }

    private fun numero(valor: Double) = if (valor.isFinite()) String.format(Locale.US, "%.2f", valor) else "—"

    private fun actualizarControles() {
        binding.permissionButton.setText(if (tienePermiso()) R.string.restart_camera else if (
            getPreferences(MODE_PRIVATE).getBoolean("permisoSolicitado", false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        ) R.string.open_camera_settings else R.string.allow_camera)
        binding.cameraButton.isEnabled =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putIntArray("valores", valores)
        outState.putBoolean("gpu", modoActual.gpu)
        outState.putInt("vista", vista)
        outState.putInt("camara", indiceCamara)
        super.onSaveInstanceState(outState)
    }

    external fun stringFromJNI(): String
    external fun procesarFrame(entrada: Long, salida: Long, valores: IntArray,
                              gpu: Boolean, vista: Int, comparar: Boolean): DoubleArray
    external fun informacionGpu(): String
    external fun reiniciarProcesador()

    companion object {
        init { System.loadLibrary("interciclovision") }
    }
}
