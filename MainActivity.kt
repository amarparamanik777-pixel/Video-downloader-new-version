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

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var urlBox: EditText
    private lateinit var presetBox: Spinner
    private lateinit var goBtn: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView

    private fun video(height: Int?): List<Pair<String, String?>> {
        val cap = if (height == null) "" else "[height<=?$height]"
        return listOf("-f" to "bv*$cap+ba/b$cap/b", "-S" to "res,ext:mp4:m4a", "--merge-output-format" to "mp4")
    }

    private fun audio(format: String, quality: String = "0"): List<Pair<String, String?>> =
        listOf("-f" to "bestaudio/best", "-x" to null, "--audio-format" to format, "--audio-quality" to quality)

    private val presets: List<Pair<String, List<Pair<String, String?>>>> = listOf(
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
        add(TextView(this).apply { text = "Paste a YouTube, Instagram or Facebook link, pick a quality or audio format, then tap Download." })
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
            }
        })
        presetBox = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presets.map { it.first })
        }
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
                status.text = error ?: "Ready. Paste a link and tap Download."
                goBtn.isEnabled = error == null
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
        bar.visibility = View.VISIBLE

        // Shows how many MB have been downloaded so far.
        val ticker = object : Runnable {
            override fun run() {
                val mb = tmp.walk().filter { it.isFile }.sumOf { it.length() } / 1048576.0
                status.text = "Downloading... %.1f MB".format(mb)
                main.postDelayed(this, 700)
            }
        }
        main.post(ticker)

        Thread {
            var result = ""
            try {
                val req = YoutubeDLRequest(url)
                req.addOption("-o", "${tmp.absolutePath}/%(title|video).120B.%(ext)s")
                req.addOption("--no-playlist")
                req.addOption("--playlist-items", "1")
                req.addOption("--no-mtime")
                for ((k, v) in opts) if (v == null) req.addOption(k) else req.addOption(k, v)
                YoutubeDL.getInstance().execute(req)
                val file = tmp.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") }
                    ?.maxByOrNull { it.length() }
                    ?: throw IllegalStateException("Nothing was downloaded.")
                result = "Saved to Downloads/VideoDownloader/" + save(file)
            } catch (e: Throwable) {
                result = friendly(e.message)
            }
            tmp.deleteRecursively()
            runOnUiThread {
                main.removeCallbacks(ticker)
                bar.visibility = View.GONE
                status.text = result
                goBtn.isEnabled = true
            }
        }.start()
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
        if (Regex("cookies|log ?in|sign in", RegexOption.IGNORE_CASE).containsMatchIn(m)) {
            return "This link is asking for a login or bot check, which this app can't handle yet."
        }
        return (m.lines().lastOrNull { it.isNotBlank() } ?: m).take(300)
    }
}
