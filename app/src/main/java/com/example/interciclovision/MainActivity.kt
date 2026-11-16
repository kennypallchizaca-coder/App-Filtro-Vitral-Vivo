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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.example.interciclovision.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.io.IOException
import java.time.Instant
import java.util.Locale

class MainActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {
    private lateinit var binding: ActivityMainBinding
    @Volatile private var valores = ParametrosMoviles.iniciales()
    @Volatile private var usarGpu = false
    @Volatile private var vista = 0
    @Volatile private var reanudada = false
    @Volatile private var camaraActiva = false
    @Volatile private var falloFrame = false
    @Volatile private var ultimoFrame = 0L
    private var indiceCamara = CameraBridgeViewBase.CAMERA_ID_FRONT
    private var salida: Mat? = null
    private val sliders = mutableListOf<SeekBar>()
    private val registro = RegistroMediciones()
    private var csvGuardado = ""
    private var csvParaGuardar = ""
    private var guardandoCsv = false
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
    private val guardarCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) escribirCsv(uri)
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
        vista = (savedInstanceState?.getInt("vista") ?: 0).coerceIn(0, 8)
        indiceCamara = savedInstanceState?.getInt("camara") ?: if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        ) CameraBridgeViewBase.CAMERA_ID_FRONT else CameraBridgeViewBase.CAMERA_ID_BACK
        csvGuardado = savedInstanceState?.getString("csv") ?: ""
        csvParaGuardar = savedInstanceState?.getString("csvPendiente") ?: ""

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
                    // Publish a new array; the camera never reads a half-updated preset.
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
        binding.measureButton.setOnClickListener {
            if (synchronized(registro) { registro.activa }) {
                synchronized(registro) { registro.detener() }
                actualizarControles()
            } else if (textoCsv().isNotEmpty()) {
                MaterialAlertDialogBuilder(this).setTitle(R.string.new_measurement)
                    .setMessage(R.string.replace_measurement)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.replace) { _, _ -> iniciarMedicion() }.show()
            } else iniciarMedicion()
        }
        binding.exportButton.setOnClickListener {
            csvParaGuardar = textoCsv()
            if (csvParaGuardar.isNotEmpty())
                guardarCsv.launch("vision-mediciones-${System.currentTimeMillis()}.csv")
        }
    }

    private fun iniciarMedicion() {
        synchronized(registro) { registro.iniciar(valores, vista) }
        csvGuardado = ""
        actualizarControles()
    }

    private fun escribirCsv(uri: Uri) {
        val datos = csvParaGuardar
        if (datos.isEmpty()) return
        val resolver = applicationContext.contentResolver
        guardandoCsv = true
        actualizarControles()
        // A document provider may be slow; never block camera controls on its I/O.
        Thread({
            val error = try {
                val stream = resolver.openOutputStream(uri) ?: throw IOException("No se abrio el archivo")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(datos) }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            handler.post {
                guardandoCsv = false
                if (!isDestroyed) {
                    val mensaje = if (error == null) getString(R.string.csv_saved)
                        else getString(R.string.csv_error, error)
                    Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
                    actualizarControles()
                }
            }
        }, "guardar-csv").start()
    }

    private fun tienePermiso() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    private fun activarCamara() {
        if (!::binding.isInitialized || !reanudada || !tienePermiso()) return
        falloFrame = false
        ultimoFrame = SystemClock.elapsedRealtime()
        binding.emptyPreview.setText(R.string.camera_wait)
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
        camaraActiva = true
        runOnUiThread {
            binding.emptyPreview.visibility = View.GONE
            actualizarControles()
        }
    }

    override fun onCameraViewStopped() {
        camaraActiva = false
        salida?.release()
        salida = null
        reiniciarProcesador()
        synchronized(registro) { registro.detener() }
        runOnUiThread { if (!isDestroyed) actualizarControles() }
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        val rgba = inputFrame.rgba()
        ultimoFrame = SystemClock.elapsedRealtime()
        val destino = salida ?: return rgba
        if (!reanudada || falloFrame) return rgba
        try {
            val comparar: Boolean
            val idMedicion: Long
            val parametros: IntArray
            val vistaActual: Int
            synchronized(registro) {
                comparar = registro.activa
                idMedicion = registro.generacion
                parametros = if (comparar) registro.valores() else valores
                vistaActual = if (comparar) registro.vista else vista
            }
            val t = procesarFrame(rgba.nativeObjAddr, destino.nativeObjAddr,
                parametros, usarGpu, vistaActual, comparar)
            actualizarRecursos()
            val dispositivo = informacionGpu()
            var termino = false
            synchronized(registro) {
                if (comparar && registro.activa) {
                    registro.agregar(idMedicion, t, rgba.cols(), rgba.rows(), recursos, dispositivo, Instant.now().toString())
                    termino = !registro.activa
                }
            }
            val ahora = SystemClock.elapsedRealtime()
            if (ahora - ultimaUi >= 500 || termino) {
                ultimaUi = ahora
                val r = recursos
                val comparacionVertical = rgba.rows() > rgba.cols()
                runOnUiThread {
                    if (!isDestroyed && reanudada) {
                        if (vistaActual == 0 && vista == 0) binding.viewCaption.setText(
                            if (comparacionVertical) R.string.comparison_vertical else R.string.comparison_horizontal)
                        binding.metrics.text = getString(R.string.live_metrics, numero(t[0]), numero(t[1]),
                            numero(r.fps), numero(r.ramPssMiB), numero(r.cpuPorcentaje), numero(r.ramLibreMiB))
                        binding.status.text = getString(R.string.live_backend,
                            if (t[5] == 1.0) "UMat / OpenCL" else "CPU / Mat", dispositivo.take(220))
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

    private fun textoCsv() = synchronized(registro) { registro.csv().ifEmpty { csvGuardado } }
    private fun numero(valor: Double) = if (valor.isFinite()) String.format(Locale.US, "%.2f", valor) else "—"

    private fun actualizarControles() {
        val midiendo = synchronized(registro) {
            if (registro.activa) binding.measurementStatus.text =
                getString(R.string.measurement_progress, registro.pares)
            else if (registro.pares > 0) binding.measurementStatus.text =
                if (registro.promedioGpuMs.isFinite()) resources.getQuantityString(R.plurals.measurement_summary,
                    registro.pares, registro.pares, numero(registro.promedioCpuMs), numero(registro.promedioGpuMs))
                else resources.getQuantityString(R.plurals.measurement_cpu_only,
                    registro.pares, registro.pares, numero(registro.promedioCpuMs))
            registro.activa
        }
        binding.measureButton.isEnabled = camaraActiva
        binding.measureButton.setText(if (midiendo) R.string.stop_measurement else R.string.start_measurement)
        binding.exportButton.isEnabled = !midiendo && !guardandoCsv && textoCsv().isNotEmpty()
        binding.exportButton.setText(if (guardandoCsv) R.string.csv_saving else R.string.export_csv)
        binding.permissionButton.isEnabled = !midiendo
        binding.permissionButton.setText(if (tienePermiso()) R.string.restart_camera else if (
            getPreferences(MODE_PRIVATE).getBoolean("permisoSolicitado", false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        ) R.string.open_camera_settings else R.string.allow_camera)
        binding.cameraButton.isEnabled = !midiendo &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        binding.resetButton.isEnabled = !midiendo
        binding.cpuButton.isEnabled = !midiendo
        binding.gpuButton.isEnabled = !midiendo
        binding.viewSelector.isEnabled = !midiendo
        sliders.forEach { it.isEnabled = !midiendo }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putIntArray("valores", valores)
        outState.putBoolean("gpu", usarGpu)
        outState.putInt("vista", vista)
        outState.putInt("camara", indiceCamara)
        outState.putString("csv", textoCsv())
        outState.putString("csvPendiente", csvParaGuardar)
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
