package com.orbiecosystem.omnivoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
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
    private var roundTripFile: File? = null
    private var engine: OmniVoiceEngine? = null
    private var roundTripEngine: HiggsRoundTrip? = null
    private var downloader: ModelDownloader? = null
    private var player: MediaPlayer? = null
    private var isRecording = false

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
    private lateinit var recButton: Button
    private lateinit var stopButton: Button
    private lateinit var playReference: Button
    private lateinit var roundTrip: Button
    private lateinit var playRoundTrip: Button

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
        root.addView(text("Voice cloning local · Android diagnostic v0.3 · POCO X7 Pro", 14f, false))
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
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(18)))
        root.addView(space(12))

        root.addView(section("3 · Reference Voice"))
        refLabel = text("Referencia: no seleccionada", 14f, false)
        root.addView(refLabel)

        val recordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recButton = Button(this).apply {
            text = "● Grabar"
            setOnClickListener { startRecording() }
        }
        stopButton = Button(this).apply {
            text = "■ Detener"
            isEnabled = false
            setOnClickListener { stopRecording() }
        }
        val pick = Button(this).apply {
            text = "Elegir WAV"
            setOnClickListener { pickWav() }
        }
        recordRow.addView(recButton, LinearLayout.LayoutParams(0, -2, 1f))
        recordRow.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
        recordRow.addView(pick, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(recordRow)

        val diagnosticRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playReference = Button(this).apply {
            text = "▶ Escuchar referencia"
            isEnabled = false
            setOnClickListener { referenceFile?.let { playFile(it, "referencia") } }
        }
        roundTrip = Button(this).apply {
            text = "TEST CODEC"
            isEnabled = false
            setOnClickListener { runRoundTrip() }
        }
        diagnosticRow.addView(playReference, LinearLayout.LayoutParams(0, -2, 1f))
        diagnosticRow.addView(roundTrip, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(diagnosticRow)

        playRoundTrip = Button(this).apply {
            text = "▶ Escuchar reconstrucción del codec"
            isEnabled = false
            setOnClickListener { roundTripFile?.let { playFile(it, "round-trip") } }
        }
        root.addView(playRoundTrip)

        refText = edit("Transcripción EXACTA del audio de referencia", 3)
        root.addView(refText)
        root.addView(text("Recomendado: 5–8 s, voz limpia, sin música.", 12f, false))
        root.addView(space(12))

        root.addView(section("4 · Synthesis"))
        targetText = edit("Texto a sintetizar", 4).apply {
            setText("Hola, esta es una prueba.")
        }
        root.addView(targetText)

        backend = spinner(listOf("CPU", "XNNPACK", "NNAPI")).apply { setSelection(1) }
        root.addView(labelled("Backend", backend))
        steps = spinner(listOf("4", "8", "16", "32")).apply { setSelection(2) }
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
            setOnClickListener { outputFile?.let { playFile(it, "resultado") } }
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
            "Diagnóstico: primero compara la referencia original con la reconstrucción TEST CODEC. " +
                "Si ambas se entienden igual, el codec Higgs está sano y el problema está en la generación OmniVoice.\n\n" +
                "Licencia: este laboratorio no redistribuye pesos. Los descarga desde Hugging Face. " +
                "Trata los pesos derivados de OmniVoice como I+D/no comercial hasta aclarar por separado su licencia comercial.",
            12f, false
        ))

        return scroll
    }

    private fun showDevice() {
        val d = DeviceInfo.read(this)
        deviceText.text = d.pretty() +
            "\nPerfil de prueba: POCO X7 Pro / Dimensity 8400-Ultra → XNNPACK; diagnóstico codec antes de seguir afinando steps."
    }

    private fun refreshModelState() {
        val ok = ModelCatalog.isComplete(filesDir)
        val missing = ModelCatalog.describeMissing(filesDir)
        progress.visibility = View.GONE
        appendStatus(
            if (ok) "MODEL PACK: READY"
            else "MODEL PACK: faltan ${missing.size} archivos\n" + missing.take(4).joinToString("\n")
        )
    }

    private fun startDownload() {
        if (ModelCatalog.isComplete(filesDir)) {
            progress.visibility = View.GONE
            appendStatus("MODEL PACK: READY · no es necesario descargar nuevamente")
            return
        }
        generate.isEnabled = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
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
                    progress.visibility = View.GONE
                    refreshModelState()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    generate.isEnabled = true
                    progress.visibility = View.GONE
                    appendStatus("DOWNLOAD ERROR: ${t.message}")
                }
            }
        }
    }

    private fun startRecording() {
        if (isRecording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), recordPermissionCode)
            return
        }
        try {
            recorder.start()
            isRecording = true
            recButton.isEnabled = false
            stopButton.isEnabled = true
            playReference.isEnabled = false
            roundTrip.isEnabled = false
            appendStatus("Grabando referencia… habla 5–8 segundos.")
            refLabel.text = "Referencia: GRABANDO…"
        } catch (t: Throwable) {
            isRecording = false
            recButton.isEnabled = true
            stopButton.isEnabled = false
            appendStatus("REC ERROR: ${t.message}")
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            toast("No hay una grabación activa.")
            return
        }
        try {
            val f = File(filesDir, "references/reference_${System.currentTimeMillis()}.wav")
            f.parentFile?.mkdirs()
            val saved = recorder.stopToWav(f)
            require(saved.isFile && saved.length() > 44) { "La grabación WAV no se creó correctamente" }
            referenceFile = saved
            val w = WavIO.readPcm16(saved)
            val sec = w.samples.size.toFloat() / w.sampleRate
            refLabel.text = "Referencia: ${saved.name} · %.1f s".format(sec)
            playReference.isEnabled = true
            roundTrip.isEnabled = ModelCatalog.isComplete(filesDir)
            appendStatus("Referencia guardada · %.1f s · ${w.sampleRate} Hz".format(sec))
        } catch (t: Throwable) {
            appendStatus("STOP REC ERROR: ${t.message}")
        } finally {
            isRecording = false
            recButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == recordPermissionCode && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
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
            val f = File(filesDir, "references/imported_reference_${System.currentTimeMillis()}.wav")
            f.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)!!.use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            val w = WavIO.readPcm16(f)
            referenceFile = f
            runOnUiThread {
                val sec = w.samples.size.toFloat() / w.sampleRate
                refLabel.text = "Referencia: ${f.name} · %.1f s".format(sec)
                playReference.isEnabled = true
                roundTrip.isEnabled = ModelCatalog.isComplete(filesDir)
                appendStatus("WAV importado · %.1f s · ${w.sampleRate} Hz".format(sec))
            }
        } catch (t: Throwable) {
            runOnUiThread { appendStatus("IMPORT ERROR: ${t.message}") }
        }
    }

    private fun runRoundTrip() {
        val ref = referenceFile ?: run {
            toast("Primero graba o carga una referencia WAV.")
            return
        }
        if (!ModelCatalog.isComplete(filesDir)) {
            toast("Primero descarga el Model Pack.")
            return
        }

        val selectedBackend = Backend.valueOf(backend.selectedItem.toString())
        val out = File(filesDir, "outputs/higgs_roundtrip_${System.currentTimeMillis()}.wav")
        roundTrip.isEnabled = false
        playRoundTrip.isEnabled = false
        appendStatus("ROUND-TRIP START · $selectedBackend")

        work.execute {
            try {
                roundTripEngine?.close()
                roundTripEngine = HiggsRoundTrip(filesDir, selectedBackend) { s ->
                    runOnUiThread { appendStatus(s) }
                }
                val stats = roundTripEngine!!.run(ref, out)
                roundTripFile = stats.outputFile
                runOnUiThread {
                    roundTrip.isEnabled = true
                    playRoundTrip.isEnabled = true
                    appendStatus(
                        "ROUND-TRIP READY\n" +
                            "frames=${stats.frames}\n" +
                            "encode=${stats.encodeMs} ms\n" +
                            "decode=${stats.decodeMs} ms\n" +
                            "total=${stats.totalMs} ms\n" +
                            "input=%.2f s · output=%.2f s".format(stats.inputSeconds, stats.outputSeconds)
                    )
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    roundTrip.isEnabled = true
                    appendStatus("ROUND-TRIP ERROR: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
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
                            "audio=%.2f s".format(stats.outputSeconds)
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

    private fun playFile(file: File, label: String) {
        try {
            require(file.isFile) { "Archivo no encontrado" }
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            appendStatus("PLAY · $label")
        } catch (t: Throwable) {
            appendStatus("PLAY ERROR ($label): ${t.message}")
        }
    }

    private fun appendStatus(s: String) {
        val lines = (status.text.toString() + "\n" + s).lines()
        status.text = lines.takeLast(80).joinToString("\n")
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
        roundTripEngine?.close()
        player?.release()
        work.shutdownNow()
        super.onDestroy()
    }
}
