package com.example.videodownloader

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var urlBox: EditText
    private lateinit var presetBox: Spinner
    private lateinit var goBtn: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var updateBtn: Button
    @Volatile private var note = ""
    @Volatile private var ready = false
    private lateinit var checkBtn: Button
    private lateinit var presetAdapter: ArrayAdapter<String>

    private fun video(height: Int?): List<Pair<String, String?>> {
        val cap = if (height == null) "" else "[height<=?$height]"
        return listOf("-f" to "bv*$cap+ba/b$cap/b", "-S" to "res,ext:mp4:m4a", "--merge-output-format" to "mp4")
    }

    private fun audio(format: String, quality: String = "0"): List<Pair<String, String?>> =
        listOf("-f" to "bestaudio/best", "-x" to null, "--audio-format" to format, "--audio-quality" to quality)

    private var presets: List<Pair<String, List<Pair<String, String?>>>> = listOf(
        "Video, best quality (MP4)" to video(null),
        "Video 2160p 4K (MP4)" to video(2160),
        "Video 1440p (MP4)" to video(1440),
        "Video 1080p (MP4)" to video(1080),
        "Video 720p (MP4)" to video(720),
        "Video 480p (MP4)" to video(480),
        "Video 360p (MP4)" to video(360),
        "Video 144p (MP4)" to video(144),
        "Audio MP3, best" to audio("mp3"),
        "Audio MP3, 192 kbps" to audio("mp3", "192K"),
        "Audio MP3, 128 kbps" to audio("mp3", "128K"),
        "Audio M4A" to audio("m4a"),
        "Audio Opus" to audio("opus"),
        "Audio WAV" to audio("wav"),
        "Audio FLAC" to audio("flac"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun add(v: View) =
            col.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad / 2 })

        add(TextView(this).apply { text = "Video Downloader"; textSize = 26f; setTypeface(null, Typeface.BOLD) })
        add(TextView(this).apply { text = "Paste a YouTube, Instagram or Facebook link and tap Check qualities and size. Then pick one and tap Download." })
        urlBox = EditText(this).apply {
            hint = "https://..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        add(urlBox)
        add(Button(this).apply {
            text = "Paste from clipboard"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.let { urlBox.setText(it) }
                if (ready && urlBox.text.toString().trim().startsWith("http")) checkLink()
            }
        })
        checkBtn = Button(this).apply {
            text = "Check qualities and size"
            isEnabled = false
            setOnClickListener { checkLink() }
        }
        add(checkBtn)
        presetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.first }.toMutableList())
        presetBox = Spinner(this).apply { adapter = presetAdapter }
        add(presetBox)
        goBtn = Button(this).apply { text = "Download"; isEnabled = false; setOnClickListener { startDownload() } }
        add(goBtn)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        add(bar)
        status = TextView(this).apply { text = "Setting up the download engine. The first launch takes a few seconds." }
        add(status)
        updateBtn = Button(this).apply {
            text = "Update engine (fixes YouTube errors)"
            isEnabled = false
            setOnClickListener { runUpdate() }
        }
        add(updateBtn)
        setContentView(ScrollView(this).apply { addView(col) })

        // A link shared from YouTube, Instagram or Facebook lands in the box.
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
            Regex("https?://\\S+").find(shared)?.let { urlBox.setText(it.value) }
        }

        Thread {
            val error = try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                null
            } catch (e: Throwable) {
                "Setup failed: ${e.message}"
            }
            runOnUiThread {
                if (error == null) {
                    runUpdate()
                } else {
                    status.text = error
                }
            }
        }.start()
    }

    private fun startDownload() {
        val url = urlBox.text.toString().trim()
        if (!url.startsWith("http")) {
            status.text = "Paste a full link first."
            return
        }
        val opts = presets[presetBox.selectedItemPosition].second
        val tmp = File(getExternalFilesDir(null) ?: filesDir, "tmp/${UUID.randomUUID()}").apply { mkdirs() }
        goBtn.isEnabled = false
        checkBtn.isEnabled = false
        bar.visibility = View.VISIBLE

        // Shows how many MB have been downloaded so far.
        val ticker = object : Runnable {
            override fun run() {
                val mb = tmp.walk().filter { it.isFile }.sumOf { it.length() } / 1048576.0
                status.text = (if (note.isEmpty()) "" else "$note ") + "Downloading... %.1f MB".format(mb)
                main.postDelayed(this, 700)
            }
        }
        main.post(ticker)

        Thread {
            var result = ""
            try {
                val isYoutube = Regex("youtube\\.com|youtu\\.be", RegexOption.IGNORE_CASE).containsMatchIn(url)
                val attempts = if (isYoutube) {
                    listOf<String?>(null, "youtube:player_client=default,-android_vr")
                } else {
                    listOf<String?>(null)
                }
                var failure: Throwable? = null
                var done = false
                for ((i, extra) in attempts.withIndex()) {
                    if (i > 0) {
                        note = "Retrying another way."
                        tmp.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    try {
                        val req = YoutubeDLRequest(url)
                        req.addOption("-o", "${tmp.absolutePath}/%(title|video).120B.%(ext)s")
                        req.addOption("--no-playlist")
                        req.addOption("--playlist-items", "1")
                        req.addOption("--no-mtime")
                        if (extra != null) req.addOption("--extractor-args", extra)
                        for ((k, v) in opts) if (v == null) req.addOption(k) else req.addOption(k, v)
                        YoutubeDL.getInstance().execute(req)
                        done = true
                        break
                    } catch (e: Throwable) {
                        failure = e
                        if (e.message?.contains("403") != true) break
                    }
                }
                if (!done) throw failure ?: IllegalStateException("Download failed.")
                val file = tmp.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") }
                    ?.maxByOrNull { it.length() }
                    ?: throw IllegalStateException("Nothing was downloaded.")
                result = "Saved to Downloads/VideoDownloader/" + save(file)
            } catch (e: Throwable) {
                result = friendly(e.message)
            }
            tmp.deleteRecursively()
            note = ""
            runOnUiThread {
                main.removeCallbacks(ticker)
                bar.visibility = View.GONE
                status.text = result
                goBtn.isEnabled = true
                checkBtn.isEnabled = true
            }
        }.start()
    }

    // Looks up which qualities exist and roughly how much data each download needs.
    private fun checkLink() {
        val url = urlBox.text.toString().trim()
        if (!ready) {
            status.text = "Please wait, the engine is still starting."
            return
        }
        if (!url.startsWith("http")) {
            status.text = "Paste a full link first."
            return
        }
        goBtn.isEnabled = false
        checkBtn.isEnabled = false
        bar.visibility = View.VISIBLE
        status.text = "Checking which qualities are available..."
        Thread {
            var found: List<Pair<String, List<Pair<String, String?>>>>? = null
            var message = ""
            try {
                val req = YoutubeDLRequest(url)
                req.addOption("--dump-single-json")
                req.addOption("--no-playlist")
                req.addOption("--playlist-items", "1")
                req.addOption("--no-warnings")
                val out = YoutubeDL.getInstance().execute(req).out
                val start = out.indexOf('{')
                val end = out.lastIndexOf('}')
                if (start < 0 || end < start) throw IllegalStateException("No details found for that link.")
                val root = JSONObject(out.substring(start, end + 1))
                val v = root.optJSONArray("entries")?.optJSONObject(0) ?: root
                val list = buildPresets(v)
                found = list
                message = "Found " + list.size + " options for \"" + v.optString("title") +
                    "\". Sizes are estimates of the data each download uses. Pick one, then tap Download."
            } catch (e: Throwable) {
                message = friendly(e.message)
            }
            runOnUiThread {
                found?.let { list ->
                    presets = list
                    presetAdapter.clear()
                    presetAdapter.addAll(list.map { it.first })
                    presetAdapter.notifyDataSetChanged()
                    presetBox.setSelection(0)
                }
                bar.visibility = View.GONE
                status.text = message
                goBtn.isEnabled = true
                checkBtn.isEnabled = true
            }
        }.start()
    }

    private fun buildPresets(v: JSONObject): List<Pair<String, List<Pair<String, String?>>>> {
        val duration = v.optDouble("duration", 0.0)
        val arr = v.optJSONArray("formats") ?: JSONArray()
        val formats = (0 until arr.length()).map { arr.getJSONObject(it) }
        fun bytesOf(f: JSONObject): Double {
            val exact = f.optDouble("filesize", 0.0)
            if (exact > 0) return exact
            val approx = f.optDouble("filesize_approx", 0.0)
            if (approx > 0) return approx
            val kbps = f.optDouble("tbr", 0.0)
            return if (kbps > 0 && duration > 0) kbps * 125.0 * duration else 0.0
        }
        fun isVideo(f: JSONObject) = f.optString("vcodec", "none") != "none" && f.optInt("height", 0) > 0
        fun abr(f: JSONObject) = f.optDouble("abr", f.optDouble("tbr", 0.0))
        val audioOnly = formats.filter {
            it.optString("vcodec", "none") == "none" && it.optString("acodec", "none") != "none"
        }
        val bestAudio = audioOnly.filter { it.optString("ext") == "m4a" }.ifEmpty { audioOnly }.maxByOrNull { abr(it) }
        val opusAudio = audioOnly.filter { it.optString("acodec").contains("opus") }.maxByOrNull { abr(it) } ?: bestAudio
        val audioBytes = bestAudio?.let { bytesOf(it) } ?: 0.0
        val byHeight = formats.filter { isVideo(it) }.groupBy { it.optInt("height") }

        val list = ArrayList<Pair<String, List<Pair<String, String?>>>>()
        for (h in byHeight.keys.sortedDescending()) {
            val fs = byHeight.getValue(h)
            val best = fs.filter { it.optString("ext") == "mp4" }.ifEmpty { fs }
                .maxByOrNull { it.optDouble("tbr", 0.0) } ?: continue
            val extra = if (best.optString("acodec", "none") != "none") 0.0 else audioBytes
            list.add(("Video ${h}p (MP4), " + sizeText(bytesOf(best) + extra)) to video(h))
        }
        if (list.isEmpty()) list.add("Video, best available" to video(null))
        val wav = duration * 176400.0
        list.add(("Audio MP3, best quality, " + sizeText(duration * 245.0 * 125.0)) to audio("mp3"))
        list.add(("Audio MP3, 192 kbps, " + sizeText(duration * 192.0 * 125.0)) to audio("mp3", "192K"))
        list.add(("Audio MP3, 128 kbps, " + sizeText(duration * 128.0 * 125.0)) to audio("mp3", "128K"))
        list.add(("Audio M4A, " + sizeText(audioBytes)) to audio("m4a"))
        list.add(("Audio Opus, " + sizeText(opusAudio?.let { bytesOf(it) } ?: 0.0)) to audio("opus"))
        list.add(("Audio WAV (very large), " + sizeText(wav)) to audio("wav"))
        list.add(("Audio FLAC (large), " + sizeText(wav * 0.6)) to audio("flac"))
        return list
    }

    private fun sizeText(bytes: Double): String = when {
        bytes <= 0.0 -> "size unknown"
        bytes >= 1e9 -> "about %.1f GB".format(bytes / 1e9)
        bytes >= 1e6 -> "about %.0f MB".format(bytes / 1e6)
        else -> "under 1 MB"
    }

    private fun runUpdate() {
        ready = false
        goBtn.isEnabled = false
        updateBtn.isEnabled = false
        checkBtn.isEnabled = false
        bar.visibility = View.VISIBLE
        status.text = "Updating the download engine. Keep your internet on."
        Thread {
            val msg = updateEngine()
            runOnUiThread {
                bar.visibility = View.GONE
                status.text = msg
                goBtn.isEnabled = true
                updateBtn.isEnabled = true
                checkBtn.isEnabled = true
                ready = true
                if (urlBox.text.toString().trim().startsWith("http")) checkLink()
            }
        }.start()
    }

    // Updates yt-dlp to the newest nightly build, because YouTube changes often.
    private fun updateEngine(): String = try {
        val yd = YoutubeDL.getInstance()
        val m = yd.javaClass.methods.filter { it.name == "updateYoutubeDL" }.maxByOrNull { it.parameterTypes.size }
            ?: throw IllegalStateException("this version cannot update")
        val args = arrayListOf<Any?>(application)
        if (m.parameterTypes.size > 1) {
            val c = m.parameterTypes[1]
            args.add(
                c.fields.firstOrNull { it.name.endsWith("NIGHTLY") }?.get(null)
                    ?: c.declaredClasses.firstOrNull { it.simpleName == "NIGHTLY" }?.getField("INSTANCE")?.get(null)
            )
        }
        val r = m.invoke(yd, *args.toTypedArray())
        "Engine ready (" + (r?.toString() ?: "updated") + "). Paste a link and tap Download."
    } catch (e: Throwable) {
        "Could not update the engine (" + (e.cause?.message ?: e.message) + "). You can still try a download."
    }

    // Copies the finished file into the public Downloads folder.
    private fun save(f: File): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoDownloader")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the file in Downloads.")
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        return f.name
    }

    private fun friendly(raw: String?): String {
        val m = (raw ?: "Download failed.").trim()
        if (m.contains("403")) {
            return "YouTube refused the download (error 403). Tap Update engine, then try again. If it keeps failing, YouTube may be blocking this video."
        }
        if (Regex("cookies|log ?in|sign in", RegexOption.IGNORE_CASE).containsMatchIn(m)) {
            return "This link is asking for a login or bot check, which this app can't handle yet."
        }
        return (m.lines().lastOrNull { it.isNotBlank() } ?: m).take(300)
    }
}
