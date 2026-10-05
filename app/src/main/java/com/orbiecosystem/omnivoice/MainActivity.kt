package com.orbiecosystem.omnivoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val work = Executors.newSingleThreadExecutor()
    private val recorder = ReferenceRecorder()
    private var referenceFile: File? = null
    private var outputFile: File? = null
    private var engine: OmniVoiceEngine? = null
    private var downloader: ModelDownloader? = null
    private var player: MediaPlayer? = null

    private lateinit var status: TextView
    private lateinit var deviceText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refText: EditText
    private lateinit var targetText: EditText
    private lateinit var backend: Spinner
    private lateinit var steps: Spinner
    private lateinit var duration: Spinner
    private lateinit var consent: CheckBox
    private lateinit var refLabel: TextView
    private lateinit var generate: Button
    private lateinit var play: Button

    private val pickWavCode = 7001
    private val recordPermissionCode = 7002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ORBI OmniVoice Edge Lab"
        setContentView(buildUi())
        showDevice()
        refreshModelState()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            setBackgroundColor(Color.rgb(245, 248, 250))
        }
        scroll.addView(root)

        root.addView(text("ORBI OmniVoice Edge Lab", 26f, true))
        root.addView(text("Voice cloning local · Android spike v0.2 · POCO X7 Pro first", 14f, false))
        root.addView(space(10))

        root.addView(section("1 · Device Readiness"))
        deviceText = TextView(this).apply { textSize = 14f }
        root.addView(deviceText)
        root.addView(space(12))

        root.addView(section("2 · Model Pack (~1.2 GB)"))
        val dl = Button(this).apply {
            text = "Descargar / reanudar modelos"
            setOnClickListener { startDownload() }
        }
        root.addView(dl)
        val verify = Button(this).apply {
            text = "Verificar modelos"
            setOnClickListener { refreshModelState() }
        }
        root.addView(verify)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(18)))
        root.addView(space(12))

        root.addView(section("3 · Reference Voice"))
        refLabel = text("Referencia: no seleccionada", 14f, false)
        root.addView(refLabel)

        val recordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rec = Button(this).apply {
            text = "● Grabar"
            setOnClickListener { startRecording() }
        }
        val stop = Button(this).apply {
            text = "■ Detener"
            setOnClickListener { stopRecording() }
        }
        val pick = Button(this).apply {
            text = "Elegir WAV"
            setOnClickListener { pickWav() }
        }
        recordRow.addView(rec, LinearLayout.LayoutParams(0, -2, 1f))
        recordRow.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        recordRow.addView(pick, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(recordRow)

        refText = edit("Transcripción EXACTA del audio de referencia", 3)
        root.addView(refText)
        root.addView(text("Recomendado: 5–8 s, voz limpia, sin música.", 12f, false))
        root.addView(space(12))

        root.addView(section("4 · Synthesis"))
        targetText = edit("Texto a sintetizar", 4).apply {
            setText("Hola, esta es una prueba de clonación local con ORBI OmniVoice.")
        }
        root.addView(targetText)

        backend = spinner(listOf("CPU", "XNNPACK", "NNAPI")).apply { setSelection(1) }
        root.addView(labelled("Backend", backend))
        steps = spinner(listOf("4", "8", "16", "32")).apply { setSelection(0) }
        root.addView(labelled("Inference steps", steps))
        duration = spinner(listOf("1.0", "1.5", "2.0", "3.0", "5.0")).apply { setSelection(2) }
        root.addView(labelled("Duración objetivo (s)", duration))

        consent = CheckBox(this).apply {
            text = "Confirmo que tengo autorización para clonar esta voz y que este build es solo I+D no comercial."
        }
        root.addView(consent)

        generate = Button(this).apply {
            text = "GENERAR VOZ LOCAL"
            setOnClickListener { generate() }
        }
        root.addView(generate)

        play = Button(this).apply {
            text = "▶ Reproducir resultado"
            isEnabled = false
            setOnClickListener { playOutput() }
        }
        root.addView(play)

        root.addView(space(14))
        root.addView(section("5 · Evidence / Status"))
        status = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(232, 238, 242))
            text = "READY"
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        root.addView(space(14))
        root.addView(text(
            "Licencia: este laboratorio no redistribuye pesos. Los descarga desde Hugging Face. " +
                "Por seguridad jurídica, trata los pesos derivados de OmniVoice como I+D/no comercial " +
                "hasta aclarar por separado la licencia comercial de los pesos upstream.",
            12f, false
        ))

        return scroll
    }

    private fun showDevice() {
        val d = DeviceInfo.read(this)
        deviceText.text = d.pretty() + "\nPerfil inicial recomendado: POCO X7 Pro / Dimensity 8400-Ultra → XNNPACK + 4 steps; luego NNAPI."
    }

    private fun refreshModelState() {
        val ok = ModelCatalog.isComplete(filesDir)
        val missing = ModelCatalog.describeMissing(filesDir)
        appendStatus(
            if (ok) "MODEL PACK: READY"
            else "MODEL PACK: faltan ${missing.size} archivos\n" + missing.take(4).joinToString("\n")
        )
    }

    private fun startDownload() {
        generate.isEnabled = false
        downloader = ModelDownloader(
            ModelCatalog.modelRoot(filesDir),
            onStatus = { s -> runOnUiThread { appendStatus(s) } },
            onProgress = { p -> runOnUiThread { progress.progress = p } }
        )
        work.execute {
            try {
                downloader!!.downloadAll()
                runOnUiThread {
                    generate.isEnabled = true
                    refreshModelState()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    generate.isEnabled = true
                    appendStatus("DOWNLOAD ERROR: ${t.message}")
                }
            }
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), recordPermissionCode)
            return
        }
        try {
            recorder.start()
            appendStatus("Grabando referencia… habla 5–8 segundos.")
            refLabel.text = "Referencia: GRABANDO…"
        } catch (t: Throwable) {
            appendStatus("REC ERROR: ${t.message}")
        }
    }

    private fun stopRecording() {
        try {
            val f = File(filesDir, "references/reference_${System.currentTimeMillis()}.wav")
            f.parentFile?.mkdirs()
            referenceFile = recorder.stopToWav(f)
            val w = WavIO.readPcm16(f)
            val sec = w.samples.size.toFloat() / w.sampleRate
            refLabel.text = "Referencia: ${f.name} · %.1f s".format(sec)
            appendStatus("Referencia guardada: ${f.absolutePath}")
        } catch (t: Throwable) {
            appendStatus("STOP REC ERROR: ${t.message}")
        }
    }

    private fun pickWav() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/wav"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, pickWavCode)
    }

    @Deprecated("legacy result API kept for min-dependency spike")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickWavCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            work.execute { copyReference(uri) }
        }
    }

    private fun copyReference(uri: Uri) {
        try {
            val f = File(filesDir, "references/imported_reference.wav")
            f.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)!!.use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            val w = WavIO.readPcm16(f)
            referenceFile = f
            runOnUiThread {
                val sec = w.samples.size.toFloat() / w.sampleRate
                refLabel.text = "Referencia: imported_reference.wav · %.1f s".format(sec)
                appendStatus("WAV importado (${w.sampleRate} Hz)")
            }
        } catch (t: Throwable) {
            runOnUiThread { appendStatus("IMPORT ERROR: ${t.message}") }
        }
    }

    private fun generate() {
        if (!consent.isChecked) {
            toast("Debes confirmar consentimiento y uso I+D.")
            return
        }
        if (!ModelCatalog.isComplete(filesDir)) {
            toast("Primero descarga el Model Pack.")
            return
        }
        val ref = referenceFile ?: run {
            toast("Graba o carga una referencia WAV.")
            return
        }
        val refTx = refText.text.toString().trim()
        val target = targetText.text.toString().trim()
        if (refTx.isBlank()) {
            toast("Escribe la transcripción exacta de la referencia.")
            return
        }

        val selectedBackend = Backend.valueOf(backend.selectedItem.toString())
        val nSteps = steps.selectedItem.toString().toInt()
        val secs = duration.selectedItem.toString().toFloat()
        outputFile = File(filesDir, "outputs/orbi_omnivoice_${System.currentTimeMillis()}.wav")
        outputFile!!.parentFile?.mkdirs()

        generate.isEnabled = false
        play.isEnabled = false
        appendStatus("START · $selectedBackend · $nSteps steps · ${secs}s")

        work.execute {
            try {
                engine?.close()
                engine = OmniVoiceEngine(filesDir, selectedBackend) { s ->
                    runOnUiThread { appendStatus(s) }
                }
                val stats = engine!!.generate(ref, refTx, target, nSteps, secs, outputFile!!)
                runOnUiThread {
                    generate.isEnabled = true
                    play.isEnabled = true
                    appendStatus(
                        "SUCCESS\n" +
                            "backend=${stats.backend}\nsteps=${stats.steps}\n" +
                            "refFrames=${stats.referenceFrames}\ngenFrames=${stats.frames}\n" +
                            "refEncode=${stats.referenceEncodeMs} ms\n" +
                            "generation=${stats.generationMs} ms\n" +
                            "decode=${stats.decodeMs} ms\n" +
                            "total=${stats.totalMs} ms\n" +
                            "audio=%.2f s\nfile=${stats.outputFile.absolutePath}".format(stats.outputSeconds)
                    )
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    generate.isEnabled = true
                    appendStatus("INFERENCE ERROR: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun playOutput() {
        val f = outputFile ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                start()
            }
        } catch (t: Throwable) {
            appendStatus("PLAY ERROR: ${t.message}")
        }
    }

    private fun appendStatus(s: String) {
        status.text = "${status.text}\n$s"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun text(s: String, size: Float, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun section(s: String): TextView =
        text(s, 18f, true).apply { setPadding(0, dp(5), 0, dp(7)) }

    private fun edit(hintText: String, lines: Int): EditText =
        EditText(this).apply {
            hint = hintText
            minLines = lines
            gravity = Gravity.TOP
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

    private fun spinner(items: List<String>): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items
            )
        }

    private fun labelled(label: String, child: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label, 12f, true))
            addView(child)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun space(h: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        downloader?.cancel()
        recorder.cancel()
        engine?.close()
        player?.release()
        work.shutdownNow()
        super.onDestroy()
    }
}
