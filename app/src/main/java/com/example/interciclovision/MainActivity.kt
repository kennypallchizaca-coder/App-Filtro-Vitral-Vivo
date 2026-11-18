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
    @Volatile private var usarGpu = false
    @Volatile private var vista = 0
    @Volatile private var reanudada = false
    @Volatile private var falloFrame = false
    @Volatile private var ultimoFrame = 0L
    private var indiceCamara = CameraBridgeViewBase.CAMERA_ID_FRONT
    private var salida: Mat? = null
    private val sliders = mutableListOf<SeekBar>()
    private var recursos = RecursosMedidos()
    private var marcaRecursos = 0L
    private var marcaCpu = 0L
    private var fotogramas = 0
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
        usarGpu = savedInstanceState?.getBoolean("gpu") ?: false
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
        binding.backend.check(if (usarGpu) R.id.gpu_button else R.id.cpu_button)
        binding.backend.setOnCheckedChangeListener { _, checked -> usarGpu = checked == R.id.gpu_button }
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
        marcaRecursos = 0L
        ultimaUi = 0L
        fotogramas = 0
        recursos = RecursosMedidos()
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
            // Ambas rutas miden el mismo fotograma; el modo elegido decide qué imagen mostrar.
            val tiempos = procesarFrame(rgba.nativeObjAddr, destino.nativeObjAddr,
                parametros, usarGpu, vistaActual, true)
            actualizarRecursos()
            val ahora = SystemClock.elapsedRealtime()
            if (ahora - ultimaUi >= 500) {
                ultimaUi = ahora
                // Asistencia de IA: OpenAI Codex limitó esta consulta JNI al ritmo de la interfaz.
                val dispositivo = informacionGpu()
                val r = recursos
                val comparacionVertical = rgba.rows() > rgba.cols()
                runOnUiThread {
                    if (!isDestroyed && reanudada) {
                        val tiempoCpuMs = tiempos[0]
                        val tiempoGpuMs = tiempos[1]
                        val gpuUsada = tiempos[5] == 1.0
                        if (vistaActual == 0 && vista == 0) binding.viewCaption.setText(
                            if (comparacionVertical) R.string.comparison_vertical else R.string.comparison_horizontal)
                        binding.metrics.text = getString(R.string.live_metrics, numero(tiempoCpuMs), numero(tiempoGpuMs),
                            numero(r.fps), numero(r.ramPssMiB), numero(r.cpuPorcentaje), numero(r.ramLibreMiB))
                        binding.status.text = getString(R.string.live_backend,
                            if (gpuUsada) "UMat / OpenCL" else "CPU / Mat", dispositivo.take(220))
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

    private fun actualizarRecursos() {
        val ahora = SystemClock.elapsedRealtime()
        fotogramas++
        if (marcaRecursos != 0L && ahora - marcaRecursos < 1000) return
        val cpu = Process.getElapsedCpuTime()
        val memoria = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoria)
        val intervalo = ahora - marcaRecursos
        recursos = RecursosMedidos(
            if (marcaRecursos == 0L) Double.NaN else fotogramas * 1000.0 / intervalo,
            if (marcaRecursos == 0L) Double.NaN else usoCpuPorcentaje(cpu - marcaCpu, intervalo,
                Runtime.getRuntime().availableProcessors()),
            Debug.getPss() / 1024.0, memoria.availMem / (1024.0 * 1024.0))
        fotogramas = 0
        marcaRecursos = ahora
        marcaCpu = cpu
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
        outState.putBoolean("gpu", usarGpu)
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
