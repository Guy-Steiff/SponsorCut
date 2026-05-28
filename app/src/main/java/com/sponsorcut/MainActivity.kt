package com.sponsorcut

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var idInput: EditText
    private lateinit var retryButton: Button
    private lateinit var pickFolderButton: Button
    private lateinit var radioFast: RadioButton
    private lateinit var radioAccurate: RadioButton
    private var pendingUri: Uri? = null
    private val prefsName = "sponsorcut_prefs"
    private val keyLastVideoId = "last_video_id"
    private val keyLastVideoIdTs = "last_video_id_ts"
    private val keyOutputFolderUri = "output_folder_uri"
    private val recentIdMaxAgeMs = 20 * 60 * 1000L
    private val tag = "SponsorCut"
    private val pickFolderRequestCode = 42

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ProcessingService.EXTRA_PROGRESS_TEXT) ?: return
            val done = intent.getBooleanExtra(ProcessingService.EXTRA_DONE, false)
            val error = intent.getBooleanExtra(ProcessingService.EXTRA_ERROR, false)
            setStatus(text)
            if (done || error) {
                // re-show the ID input in case user wants to retry a different video
                idInput.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Catch any uncaught exception and display it before crashing
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            android.util.Log.e("SponsorCutCRASH", "UNCAUGHT", throwable)
            try {
                val msg = throwable.stackTraceToString().take(2000)
                runOnUiThread {
                    try { statusView.text = "CRASH:\n$msg" } catch (_: Exception) {}
                }
                Thread.sleep(200)
            } catch (_: Exception) {}
        }

        setupUi()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 99)
            }
        }

        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ProcessingService.BROADCAST_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(progressReceiver) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFolderRequestCode && resultCode == RESULT_OK) {
            val treeUri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                .putString(keyOutputFolderUri, treeUri.toString())
                .apply()
            val path = treeUri.lastPathSegment ?: treeUri.toString()
            setStatus("Output folder set to: $path")
            pickFolderButton.text = "Output: $path"
        }
    }

    private fun setupUi() {
        val p = (16 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            text = "Share a downloaded YouTube video here from PipePipe/NewPipe."
            textSize = 15f
            setPadding(p, p, p, p)
        }

        idInput = EditText(this).apply {
            hint = "Paste YouTube URL or 11-char video ID"
            visibility = View.GONE
        }

        retryButton = Button(this).apply {
            text = "Process with this ID"
            visibility = View.GONE
            setOnClickListener {
                val uri = pendingUri
                if (uri == null) { setStatus("No shared video is pending."); return@setOnClickListener }
                val raw = idInput.text?.toString().orEmpty()
                val videoId = if (raw.length == 11 && raw.all { it.isLetterOrDigit() || it == '_' || it == '-' }) raw
                              else extractId(raw)
                if (videoId.isBlank()) { setStatus("Not a valid YouTube URL or 11-char ID."); return@setOnClickListener }
                saveId(videoId)
                idInput.visibility = View.GONE
                retryButton.visibility = View.GONE
                startProcessing(uri, videoId, "manual")
            }
        }

        val savedFolder = getSharedPreferences(prefsName, MODE_PRIVATE).getString(keyOutputFolderUri, null)
        val folderLabel = if (savedFolder != null)
            "Output: ${Uri.parse(savedFolder).lastPathSegment ?: savedFolder}"
        else
            "Pick output folder (optional)"
        pickFolderButton = Button(this).apply {
            text = folderLabel
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(i, pickFolderRequestCode)
            }
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(statusView, lp)
            addView(idInput, lp)
            addView(retryButton, lp)
            addView(pickFolderButton, lp)

            // Cut mode selector
            val modeLabel = TextView(context).apply {
                text = "Cut mode:"
                setPadding(p, p / 2, p, 0)
            }
            addView(modeLabel, lp)

            val radioGroup = RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(p, 0, p, 0)
            }
            radioFast = RadioButton(context).apply {
                id = View.generateViewId()
                text = "Fast (stream copy — default, no re-encode)"
                isChecked = true
            }
            radioAccurate = RadioButton(context).apply {
                id = View.generateViewId()
                text = "Frame-accurate (re-encode, slower, exact cuts)"
                isChecked = false
            }
            radioGroup.addView(radioFast)
            radioGroup.addView(radioAccurate)
            radioGroup.check(radioFast.id)
            addView(radioGroup, lp)

            // Signature
            val sig = TextView(context).apply {
                text = "By: Guy Steiff"
                textSize = 12f
                setPadding(p, p * 2, p, p)
                alpha = 0.5f
            }
            addView(sig, lp)
        }

        val scroll = ScrollView(this).apply {
            addView(scrollContent)
        }

        setContentView(scroll)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            "com.sponsorcut.PROCESS_FILE" -> handleDirectIntent(intent)
            Intent.ACTION_SEND -> handleShareIntent(intent)
            else -> setStatus("Waiting for a shared video from PipePipe/NewPipe.")
        }
    }

    // Called by PipePipe/NewPipe fork with both URI and video ID pre-populated
    private fun handleDirectIntent(intent: Intent) {
        val uriString = intent.getStringExtra("uri")
        val videoId = intent.getStringExtra("video_id").orEmpty()

        if (uriString.isNullOrBlank()) {
            setStatus("Direct intent missing 'uri' extra.")
            return
        }
        val uri = Uri.parse(uriString)
        pendingUri = uri

        if (videoId.length == 11) {
            saveId(videoId)
            idInput.setText(videoId)
            idInput.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
            setStatus("PipePipe/NewPipe integration ✓\nFile: $uri\nID: $videoId\n\nVerify and tap process.")
        } else {
            idInput.setText("")
            idInput.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
            setStatus("PipePipe/NewPipe integration ✓\nFile: $uri\nNo video ID provided — paste it below.")
        }
    }

    private fun handleShareIntent(intent: Intent) {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.getItemAt(0)?.uri

        if (uri == null) {
            val videoId = extractIdFromIntent(intent, Uri.EMPTY)
            if (videoId.isNotBlank()) {
                saveId(videoId)
                setStatus("Captured YouTube ID: $videoId\nNow share the downloaded video file to process.")
                return
            }
            setStatus("No video file detected. Share a downloaded video file.")
            return
        }

        pendingUri = uri
        var idSource = "intent"
        var videoId = extractIdFromIntent(intent, uri)

        if (videoId.isBlank()) {
            videoId = extractIdFromClipboard()
            if (videoId.isNotBlank()) idSource = "clipboard"
        }

        if (videoId.isBlank()) {
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val ts = prefs.getLong(keyLastVideoIdTs, 0L)
            if (System.currentTimeMillis() - ts <= recentIdMaxAgeMs) {
                videoId = prefs.getString(keyLastVideoId, "").orEmpty()
                if (videoId.isNotBlank()) idSource = "recent"
            }
        }

        Log.i(tag, "Detected id='$videoId' source=$idSource uri=$uri")

        idInput.setText(videoId)
        idInput.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        if (videoId.isBlank()) {
            setStatus("URI: $uri\nCould not detect YouTube ID. Paste URL/ID below and tap process.")
        } else {
            setStatus("URI: $uri\nAuto-detected ID: $videoId (source: $idSource)\nVerify then tap process.")
        }
    }

    private fun startProcessing(uri: Uri, videoId: String, idSource: String) {
        val outputFolderUri = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(keyOutputFolderUri, null)
        val frameAccurate = radioAccurate.isChecked

        setStatus("Starting processing… [${if (frameAccurate) "frame-accurate" else "fast"}]")

        val serviceIntent = Intent(this, ProcessingService::class.java).apply {
            action = ProcessingService.ACTION_PROCESS
            putExtra(ProcessingService.EXTRA_URI, uri.toString())
            putExtra(ProcessingService.EXTRA_VIDEO_ID, videoId)
            putExtra(ProcessingService.EXTRA_ID_SOURCE, idSource)
            putExtra(ProcessingService.EXTRA_FRAME_ACCURATE, frameAccurate)
            if (outputFolderUri != null) putExtra(ProcessingService.EXTRA_OUTPUT_FOLDER_URI, outputFolderUri)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun saveId(videoId: String) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit()
            .putString(keyLastVideoId, videoId)
            .putLong(keyLastVideoIdTs, System.currentTimeMillis())
            .apply()
    }

    private fun extractIdFromIntent(intent: Intent, uri: Uri): String {
        val candidates = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getStringExtra(Intent.EXTRA_TITLE),
            intent.dataString,
            if (uri != Uri.EMPTY) uri.toString() else null,
            intent.clipData?.getItemAt(0)?.text?.toString(),
            intent.clipData?.getItemAt(0)?.uri?.toString()
        )
        for (candidate in candidates) {
            val id = extractId(candidate)
            if (id.isNotBlank()) return id
        }
        return ""
    }

    private fun extractIdFromClipboard(): String {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        return extractId(text)
    }

    private fun extractId(text: String): String {
        if (text.isBlank()) return ""
        val decoded = Uri.decode(text)
        val patterns = listOf(
            Regex("[?&]v=([a-zA-Z0-9_-]{11})"),
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/shorts/([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/live/([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/embed/([a-zA-Z0-9_-]{11})")
        )
        for (pattern in patterns) {
            val match = pattern.find(decoded)
            if (match != null) return match.groupValues[1]
        }

        return ""
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }

    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }
}
