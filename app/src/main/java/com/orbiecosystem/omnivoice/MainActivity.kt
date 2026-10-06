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
    private var autoVoiceFile: File? = null
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
    private lateinit var speed: Spinner
    private lateinit var consent: CheckBox
    private lateinit var refLabel: TextView
    private lateinit var generate: Button
    private lateinit var play: Button
    private lateinit var generateAuto: Button
    private lateinit var playAuto: Button
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

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
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
        root.addView(text("Android ${versionName()} · AUTO duration + speed + WAV cleanup", 14f, false))
        root.addView(space(10))

        root.addView(section("1 · Device Readiness"))
        deviceText = TextView(this).apply { textSize = 14f }
        root.addView(deviceText)
        root.addView(space(12))

        root.addView(section("2 · Model Pack"))
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

        backend = spinner(listOf("CPU", "XNNPACK", "NNAPI")).apply { setSelection(0) }
        root.addView(labelled("Backend", backend))

        steps = spinner(listOf("4", "8", "16", "32")).apply { setSelection(3) }
        root.addView(labelled("Inference steps", steps))

        duration = spinner(listOf("AUTO", "1.0", "1.5", "2.0", "3.0", "5.0")).apply { setSelection(0) }
        root.addView(labelled("Duración (AUTO recomendado; manual = avanzado)", duration))

        speed = spinner(listOf("0.75", "0.90", "1.00", "1.10", "1.25")).apply { setSelection(2) }
        root.addView(labelled("Velocidad (1.00 = normal; <1 lento; >1 rápido)", speed))
        root.addView(text("En modo manual la duración fija reemplaza el control de velocidad, igual que OmniVoice upstream.", 12f, false))

        root.addView(space(8))
        root.addView(text(
            "Prueba A: TTS sin referencia. AUTO estima la cantidad de frames desde el texto.",
            12f, false
        ))
        generateAuto = Button(this).apply {
            text = "GENERAR TTS SIN REFERENCIA"
            setOnClickListener { generateAutoVoice() }
        }
        root.addView(generateAuto)
        playAuto = Button(this).apply {
            text = "▶ Reproducir TTS sin referencia"
            isEnabled = false
            setOnClickListener { autoVoiceFile?.let { playFile(it, "auto-voice") } }
        }
        root.addView(playAuto)

        root.addView(space(8))
        root.addView(text(
            "Prueba B: clonación. AUTO usa tu transcripción + duración real de la referencia para estimar tu ritmo.",
            12f, false
        ))
        consent = CheckBox(this).apply {
            text = "Confirmo que tengo autorización para clonar esta voz y que este build es solo I+D no comercial."
        }
        root.addView(consent)

        generate = Button(this).apply {
            text = "GENERAR VOZ CLONADA"
            setOnClickListener { generateClone() }
        }
        root.addView(generate)

        play = Button(this).apply {
            text = "▶ Reproducir resultado clonado"
            isEnabled = false
            setOnClickListener { outputFile?.let { playFile(it, "resultado clonado") } }
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
            "v0.6: duración AUTO inspirada en RuleDurationEstimator upstream, control speed y postproceso conservador. " +
                "Las salidas finales se publican como WAV en Descargas/ORBI OmniVoice.\n\n" +
                "Licencia: laboratorio I+D; los pesos no se redistribuyen dentro del APK.",
            12f, false
        ))

        return scroll
    }

    private fun showDevice() {
        val d = DeviceInfo.read(this)
        deviceText.text = d.pretty() +
            "\nPerfil: POCO X7 Pro / Dimensity 8400-Ultra. Backbone bidireccional en CPU correctness path."
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

    private fun setInferenceButtons(enabled: Boolean) {
        generate.isEnabled = enabled
        generateAuto.isEnabled = enabled
    }

    private fun startDownload() {
        if (ModelCatalog.isComplete(filesDir)) {
            progress.visibility = View.GONE
            appendStatus("MODEL PACK: READY · no es necesario descargar nuevamente")
            return
        }
        setInferenceButtons(false)
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
                    setInferenceButtons(true)
                    progress.visibility = View.GONE
                    refreshModelState()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setInferenceButtons(true)
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
            setReference(saved, "Referencia guardada")
        } catch (t: Throwable) {
            appendStatus("STOP REC ERROR: ${t.message}")
        } finally {
            isRecording = false
            recButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun setReference(file: File, message: String) {
        referenceFile = file
        val w = WavIO.readPcm16(file)
        val sec = w.samples.size.toFloat() / w.sampleRate
        refLabel.text = "Referencia: ${file.name} · %.1f s".format(sec)
        playReference.isEnabled = true
        roundTrip.isEnabled = ModelCatalog.isComplete(filesDir)
        appendStatus("$message · %.1f s · ${w.sampleRate} Hz".format(sec))
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
            runOnUiThread {
                referenceFile = f
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

    private fun selectedManualSeconds(): Float? {
        val value = duration.selectedItem.toString()
        return if (value == "AUTO") null else value.toFloat()
    }

    private fun selectedSpeed(): Float = speed.selectedItem.toString().toFloat()

    private fun estimateAuto(target: String): RuleDurationEstimator.Estimate =
        RuleDurationEstimator.estimate(
            targetText = target,
            speed = selectedSpeed(),
            manualSeconds = selectedManualSeconds()
        )

    private fun estimateClone(target: String, refTextValue: String, ref: File): RuleDurationEstimator.Estimate {
        val w = WavIO.readPcm16(ref)
        val seconds = w.samples.size.toFloat() / w.sampleRate
        val approximateRefFrames = (seconds * RuleDurationEstimator.FRAME_RATE).toInt().coerceAtLeast(1)
        return RuleDurationEstimator.estimate(
            targetText = target,
            referenceText = refTextValue,
            referenceFrames = approximateRefFrames,
            speed = selectedSpeed(),
            manualSeconds = selectedManualSeconds()
        )
    }

    private fun postProcess(raw: File, finalFile: File): Float {
        val wav = WavIO.readPcm16(raw)
        val clean = AudioPostProcessor.process(wav.samples, wav.sampleRate)
        WavIO.writePcm16(finalFile, clean, wav.sampleRate)
        raw.delete()
        return clean.size.toFloat() / wav.sampleRate
    }

    private fun generationModeLabel(estimate: RuleDurationEstimator.Estimate): String {
        val speedLabel = if (estimate.source == "MANUAL") "speed=IGNORED" else "speed=${selectedSpeed()}x"
        val clamp = if (estimate.frames != estimate.unclampedFrames) " · clamp ${estimate.unclampedFrames}→${estimate.frames}" else ""
        return "${estimate.source} · ${estimate.frames} frames · %.2fs · $speedLabel$clamp".format(estimate.seconds)
    }

    private fun generateAutoVoice() {
        if (!ModelCatalog.isComplete(filesDir)) {
            toast("Primero descarga el Model Pack.")
            return
        }
        val target = targetText.text.toString().trim()
        if (target.isBlank()) {
            toast("Escribe el texto a sintetizar.")
            return
        }

        val selectedBackend = Backend.valueOf(backend.selectedItem.toString())
        val nSteps = steps.selectedItem.toString().toInt()
        val estimate = estimateAuto(target)
        val stamp = System.currentTimeMillis()
        val raw = File(filesDir, "outputs/raw_auto_voice_$stamp.wav")
        val finalOut = File(filesDir, "outputs/auto_voice_$stamp.wav")
        raw.parentFile?.mkdirs()
        autoVoiceFile = null

        setInferenceButtons(false)
        playAuto.isEnabled = false
        appendStatus("AUTO START · $selectedBackend · $nSteps steps\n${generationModeLabel(estimate)}")

        work.execute {
            try {
                engine?.close()
                engine = OmniVoiceEngine(filesDir, selectedBackend) { s ->
                    runOnUiThread { appendStatus(s) }
                }
                val stats = engine!!.generateAutoVoice(target, nSteps, estimate.seconds, raw)
                val cleanSeconds = postProcess(raw, finalOut)
                autoVoiceFile = finalOut
                runOnUiThread {
                    setInferenceButtons(true)
                    playAuto.isEnabled = true
                    appendStatus(
                        "AUTO SUCCESS\n" +
                            "backend=${stats.backend}\nsteps=${stats.steps}\n" +
                            "genFrames=${stats.frames}\n" +
                            "generation=${stats.generationMs} ms\n" +
                            "decode=${stats.decodeMs} ms\n" +
                            "total=${stats.totalMs} ms\n" +
                            "raw=%.2f s · clean=%.2f s\n".format(stats.outputSeconds, cleanSeconds) +
                            "WAV público: Descargas/ORBI OmniVoice/${finalOut.name.replaceFirst("auto_voice_", "ORBI_TTS_")}" 
                    )
                }
            } catch (t: Throwable) {
                raw.delete()
                runOnUiThread {
                    setInferenceButtons(true)
                    appendStatus("AUTO INFERENCE ERROR: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun generateClone() {
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
        if (target.isBlank()) {
            toast("Escribe el texto a sintetizar.")
            return
        }

        val selectedBackend = Backend.valueOf(backend.selectedItem.toString())
        val nSteps = steps.selectedItem.toString().toInt()
        val estimate = estimateClone(target, refTx, ref)
        val stamp = System.currentTimeMillis()
        val raw = File(filesDir, "outputs/raw_orbi_omnivoice_$stamp.wav")
        val finalOut = File(filesDir, "outputs/orbi_omnivoice_$stamp.wav")
        raw.parentFile?.mkdirs()
        outputFile = null

        setInferenceButtons(false)
        play.isEnabled = false
        appendStatus("CLONE START · $selectedBackend · $nSteps steps\n${generationModeLabel(estimate)}")

        work.execute {
            try {
                engine?.close()
                engine = OmniVoiceEngine(filesDir, selectedBackend) { s ->
                    runOnUiThread { appendStatus(s) }
                }
                val stats = engine!!.generate(ref, refTx, target, nSteps, estimate.seconds, raw)
                val cleanSeconds = postProcess(raw, finalOut)
                outputFile = finalOut
                runOnUiThread {
                    setInferenceButtons(true)
                    play.isEnabled = true
                    appendStatus(
                        "CLONE SUCCESS\n" +
                            "backend=${stats.backend}\nsteps=${stats.steps}\n" +
                            "refFrames=${stats.referenceFrames}\ngenFrames=${stats.frames}\n" +
                            "refEncode=${stats.referenceEncodeMs} ms\n" +
                            "generation=${stats.generationMs} ms\n" +
                            "decode=${stats.decodeMs} ms\n" +
                            "total=${stats.totalMs} ms\n" +
                            "raw=%.2f s · clean=%.2f s\n".format(stats.outputSeconds, cleanSeconds) +
                            "WAV público: Descargas/ORBI OmniVoice/${finalOut.name.replaceFirst("orbi_omnivoice_", "ORBI_CLONE_")}" 
                    )
                }
            } catch (t: Throwable) {
                raw.delete()
                runOnUiThread {
                    setInferenceButtons(true)
                    appendStatus("CLONE INFERENCE ERROR: ${t.javaClass.simpleName}: ${t.message}")
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
        status.text = lines.takeLast(100).joinToString("\n")
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
