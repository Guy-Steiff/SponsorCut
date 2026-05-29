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
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import android.os.Handler
import android.os.Looper

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var idInput: EditText
    private lateinit var retryButton: Button
    private lateinit var pickFolderButton: Button
    private lateinit var radioFast: RadioButton
    private lateinit var radioAccurate: RadioButton
    private lateinit var progressBar: ProgressBar
    private lateinit var browseFileButton: Button
    private lateinit var openPlayerButton: Button
    private lateinit var cancelButton: Button
    private lateinit var radioGroup: RadioGroup
    private lateinit var logButton: Button
    private lateinit var logView: TextView
    private lateinit var modeHintView: TextView
    private lateinit var idCardView: LinearLayout
    private lateinit var idCardIcon: TextView
    private lateinit var fileCardView: LinearLayout
    private lateinit var fileCardIcon: TextView
    private lateinit var fileCardLabel: TextView

    private enum class CardState { PENDING, CHECKING, OK, ERROR }
    private var idCardState = CardState.PENDING
    private var fileCardState = CardState.PENDING
    private val dotHandler = Handler(Looper.getMainLooper())
    private var dotRunnable: Runnable? = null
    private val dotPhases = arrayOf(".", "..", "...")
    private var dotPhaseIdx = 0
    private var dotBaseText = ""
    private var pendingUri: Uri? = null
    private var cachedSegments: List<SponsorSegmentInfo>? = null  // null=not fetched yet
    private var lastFetchedId: String = ""
    private var cachedTitle: String? = null
    private var cachedAuthor: String? = null
    private val prefsName = "sponsorcut_prefs"
    private val keyLastVideoId = "last_video_id"
    private val keyLastVideoIdTs = "last_video_id_ts"
    private val keyOutputFolderUri = "output_folder_uri"
    private val keyFrameAccurate = "frame_accurate"
    private val recentIdMaxAgeMs = 7 * 24 * 60 * 60 * 1000L  // 7 days
    private val tag = "SponsorCut"
    private val pickFolderRequestCode = 42
    private val browseFileRequestCode = 43

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val current = intent?.getIntExtra(ProcessingService.EXTRA_PROGRESS_CURRENT, -1) ?: -1
            val total = intent?.getIntExtra(ProcessingService.EXTRA_PROGRESS_TOTAL, -1) ?: -1
            if (current >= 0 && total > 0) {
                runOnUiThread {
                    progressBar.visibility = View.VISIBLE
                    progressBar.max = total
                    progressBar.progress = current
                }
                return
            }
            val text = intent?.getStringExtra(ProcessingService.EXTRA_PROGRESS_TEXT) ?: return
            val done = intent.getBooleanExtra(ProcessingService.EXTRA_DONE, false)
            val error = intent.getBooleanExtra(ProcessingService.EXTRA_ERROR, false)
            val cancelled = intent.getBooleanExtra(ProcessingService.EXTRA_CANCELLED, false)
            if (done || error || cancelled) {
                setStatus(if (cancelled) "⏹ Cancelled." else text)
                runOnUiThread {
                    setProcessingUi(active = false)
                    logButton.visibility = View.VISIBLE
                }
            } else {
                runOnUiThread { startDotAnim(text) }
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
        if (requestCode == browseFileRequestCode && resultCode == RESULT_OK) {
            val fileUri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            pendingUri = fileUri
            fileCardState = CardState.OK
            val fileName = FileResolver.getDisplayName(this, fileUri).ifBlank { fileUri.lastPathSegment ?: "file" }
            fileCardLabel.text = "📄 $fileName"
            browseFileButton.text = "📁 Change file…"
            updateCards()

            // Priority 1: whatever is already typed/shown in idInput (covers the URL-share→browse flow)
            val currentInput = idInput.text?.toString().orEmpty()
            val videoId = if (currentInput.length == 11 &&
                    currentInput.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }) {
                currentInput
            } else {
                extractId(currentInput)
            }
            // Do NOT fall back to the cache here — a stale cached ID from a different
            // session will silently corrupt the pairing. The user must provide the ID explicitly.

            idInput.setText(videoId)
            idInput.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
            browseFileButton.visibility = View.VISIBLE
            if (videoId.isBlank())
                setStatus("File selected ✓\nNo YouTube ID — paste URL or 11-char ID below, then tap Process.")
            else {
                setStatus("File selected ✓\nID: $videoId\nFetching title and segments…")
                fetchSegmentsForId(videoId, force = true)
            }
            applyModeRecommendation()
        }
    }

    private fun setupUi() {
        val p = (16 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            text = "Share a downloaded YouTube video here from PipePipe/NewPipe."
            textSize = 15f
            setPadding(p, p, p, p)
            setTextIsSelectable(true)
        }

        idInput = EditText(this).apply {
            hint = "Paste YouTube URL or 11-char video ID"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val raw = s?.toString().orEmpty()
                    val id = if (raw.length == 11 && raw.all { it.isLetterOrDigit() || it == '_' || it == '-' })
                        raw else extractId(raw)
                    if (id.isNotBlank()) fetchSegmentsForId(id, force = true)
                }
            })
        }

        retryButton = Button(this).apply {
            text = "Process with this ID"
            isEnabled = false
            alpha = 0.5f
            setBackgroundColor(0xFF2E7D32.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                val uri = pendingUri
                if (uri == null) { setStatus("No shared video is pending."); return@setOnClickListener }
                val raw = idInput.text?.toString().orEmpty()
                val videoId = if (raw.length == 11 && raw.all { it.isLetterOrDigit() || it == '_' || it == '-' }) raw
                              else extractId(raw)
                if (videoId.isBlank()) { setStatus("Not a valid YouTube URL or 11-char ID."); return@setOnClickListener }
                saveId(videoId)
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

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = false
        }

        cancelButton = Button(this).apply {
            text = "⏹ Cancel job"
            visibility = View.GONE
            setBackgroundColor(0xFFCC3333.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                isEnabled = false
                alpha = 0.5f
                text = "Cancelling…"
                val cancelIntent = Intent(this@MainActivity, ProcessingService::class.java).apply {
                    action = ProcessingService.ACTION_CANCEL
                }
                startService(cancelIntent)
            }
        }

        browseFileButton = Button(this).apply {
            text = "📁 Browse for video file…"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*", "application/octet-stream"))
                }
                startActivityForResult(i, browseFileRequestCode)
            }
        }

        // "Open player" button — just reminds user to copy URL; no longer launches player
        // because launching PipePipe/NewPipe via getLaunchIntentForPackage plays the video
        // rather than showing the URL to copy.
        openPlayerButton = Button(this).apply {
            text = "💡 How to get the YouTube ID"
            setOnClickListener {
                toast("In PipePipe/NewPipe/YouTube website: long-press the video → Share → copy the YouTube URL. Then paste it in the field above.")
            }
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(statusView, lp)
            addView(progressBar, lp)
            addView(cancelButton, lp)

            // ── Card 1: YouTube ID ────────────────────────────────────────────
            idCardView = makeCard(p, cardColor(CardState.PENDING)).also { card ->
                idCardIcon = TextView(context).apply {
                    text = "⏳"
                    textSize = 20f
                    setPadding(0, 0, p / 2, 0)
                }
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(idCardIcon, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(idInput, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(openPlayerButton, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                card.addView(col, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            addView(idCardView, lp)

            // ── Card 2: Video file ────────────────────────────────────────────
            fileCardView = makeCard(p, cardColor(CardState.PENDING)).also { card ->
                fileCardIcon = TextView(context).apply {
                    text = "⏳"
                    textSize = 20f
                    setPadding(0, 0, p / 2, 0)
                }
                fileCardLabel = TextView(context).apply {
                    text = "No file selected"
                    textSize = 14f
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(fileCardIcon, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(fileCardLabel, LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                card.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                card.addView(browseFileButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            addView(fileCardView, lp)

            addView(retryButton, lp)
            addView(pickFolderButton, lp)

            // Cut mode selector
            val modeLabel = TextView(context).apply {
                text = "Cut mode:"
                setPadding(p, p / 2, p, 0)
            }
            addView(modeLabel, lp)

            radioGroup = RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(p, 0, p, 0)
            }
            val savedFrameAccurate = getSharedPreferences(prefsName, MODE_PRIVATE)
                .getBoolean(keyFrameAccurate, false)  // default: Fast
            radioFast = RadioButton(context).apply {
                id = View.generateViewId()
                text = "Fast (stream copy — default, no re-encode)"
                isChecked = !savedFrameAccurate
            }
            radioAccurate = RadioButton(context).apply {
                id = View.generateViewId()
                text = "Frame-accurate (re-encode, slower, exact cuts)"
                isChecked = savedFrameAccurate
            }
            radioGroup.addView(radioFast)
            radioGroup.addView(radioAccurate)
            radioGroup.check(if (savedFrameAccurate) radioAccurate.id else radioFast.id)
            radioGroup.setOnCheckedChangeListener { _, _ ->
                getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                    .putBoolean(keyFrameAccurate, radioAccurate.isChecked)
                    .apply()
            }
            addView(radioGroup, lp)

            modeHintView = TextView(context).apply {
                visibility = View.GONE
                textSize = 13f
                setPadding(p, p / 2, p, p / 2)
            }
            addView(modeHintView, lp)

            // Signature
            val sig = TextView(context).apply {
                text = "By: Guy Steiff\nAbout: https://guysteiff.vercel.app/"
                textSize = 12f
                setPadding(p, p * 2, p, p)
                alpha = 0.5f
            }
            addView(sig, lp)

            // Diagnostic log — hidden until a run completes
            logButton = Button(context).apply {
                text = "📋 Show diagnostic log"
                visibility = View.GONE
                setOnClickListener {
                    val showing = logView.visibility == View.VISIBLE
                    logView.visibility = if (showing) View.GONE else View.VISIBLE
                    text = if (showing) "📋 Show diagnostic log" else "📋 Hide diagnostic log"
                    if (!showing) logView.text = DiagLog.get().ifBlank { "(log is empty)" }
                }
            }
            addView(logButton, lp)

            logView = TextView(context).apply {
                visibility = View.GONE
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(p, p / 2, p, p)
                setBackgroundColor(0xFF111111.toInt())
                setTextColor(0xFFCCCCCC.toInt())
            }
            addView(logView, lp)
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
            else -> {
                // Cold open — restore last remembered ID into the input field if recent enough
                val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                val savedId = prefs.getString(keyLastVideoId, "").orEmpty()
                val savedTs = prefs.getLong(keyLastVideoIdTs, 0L)
                val isRecent = System.currentTimeMillis() - savedTs <= recentIdMaxAgeMs
                if (savedId.isNotBlank() && isRecent) {
                    idInput.setText(savedId)
                    idCardState = CardState.CHECKING
                    updateCards()
                    setStatus("Welcome back — last ID: $savedId\n\nShare or browse a video file to process it.")
                    fetchSegmentsForId(savedId)
                } else {
                    setStatus("Paste a YouTube URL or ID above, then browse for your video file.")
                }
            }
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
        fileCardState = CardState.OK
        runOnUiThread {
            fileCardLabel.text = "📄 ${uri.lastPathSegment ?: uriString}"
            browseFileButton.text = "📁 Change file…"
        }

        if (videoId.length == 11) {
            idInput.setText(videoId)
            idCardState = CardState.CHECKING
            updateCards()
            setStatus("PipePipe/NewPipe ✓\nFetching segments for ID: $videoId")
            fetchSegmentsForId(videoId)
        } else {
            setStatus("PipePipe/NewPipe ✓\nNo video ID provided — paste it in the field above.")
        }
    }

    private fun handleShareIntent(intent: Intent) {
        Log.d(tag, "handleShareIntent: action=${intent.action} type=${intent.type} " +
            "EXTRA_TEXT=${intent.getStringExtra(Intent.EXTRA_TEXT)?.take(80)} " +
            "EXTRA_STREAM=${intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)}")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.getItemAt(0)?.uri

        if (uri == null) {
            val videoId = extractIdFromIntent(intent, Uri.EMPTY)
            if (videoId.isNotBlank()) {
                saveId(videoId)  // save now so file-share later will find it
                runOnUiThread { idInput.setText(videoId) }
                idCardState = CardState.CHECKING
                updateCards()
                setStatus("✓ YouTube ID captured: $videoId\n\nNow browse to your downloaded video file.")
                fetchSegmentsForId(videoId)
                return
            }
            setStatus("No video file detected. Share a downloaded video file.")
            return
        }

        pendingUri = uri
        fileCardState = CardState.OK
        val sharedFileName = FileResolver.getDisplayName(this, uri).ifBlank { uri.lastPathSegment ?: "file" }
        runOnUiThread {
            fileCardLabel.text = "📄 $sharedFileName"
            browseFileButton.text = "📁 Change file…"
        }
        var idSource = "intent"
        var videoId = extractIdFromIntent(intent, uri)

        if (videoId.isBlank()) {
            videoId = extractIdFromClipboard()
            if (videoId.isNotBlank()) idSource = "clipboard"
        }

        // If still blank, suggest the cached ID — but label it clearly so user can verify.
        // Unlike the old silent fallback, this pre-fills the field visibly so the user
        // can confirm or overwrite before tapping Process.
        if (videoId.isBlank()) {
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val savedId = prefs.getString(keyLastVideoId, "").orEmpty()
            val savedTs = prefs.getLong(keyLastVideoIdTs, 0L)
            val ageMs = System.currentTimeMillis() - savedTs
            Log.i(tag, "Cache lookup: savedId='$savedId' savedTs=$savedTs ageMs=$ageMs maxAgeMs=$recentIdMaxAgeMs")
            if (savedId.isNotBlank() && ageMs <= recentIdMaxAgeMs) {
                videoId = savedId
                idSource = "memory"
            }
        }

        Log.i(tag, "Detected id='$videoId' source=$idSource uri=$uri")

        // Persist any ID we found so it survives Activity recreation (e.g. switching to file manager)
        if (videoId.isNotBlank()) saveId(videoId)

        idInput.setText(videoId)
        if (videoId.isBlank()) {
            setStatus("No YouTube ID detected.\nPaste a URL/ID in the field above.")
        } else {
            val sourceLabel = if (idSource == "memory") "⚠️ from memory — verify!" else "source: $idSource"
            setStatus("ID: $videoId ($sourceLabel)\nFetching title and segments…")
            idCardState = CardState.CHECKING
            updateCards()
            fetchSegmentsForId(videoId, force = true)
            applyModeRecommendation()
        }
    }

    private fun startProcessing(uri: Uri, videoId: String, idSource: String) {
        val outputFolderUri = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(keyOutputFolderUri, null)
        val frameAccurate = radioAccurate.isChecked

        setProcessingUi(active = true)
        logButton.visibility = View.GONE
        logView.visibility = View.GONE
        val videoHeader = buildString {
            if (cachedTitle != null) {
                append("\"${cachedTitle}\"")
                if (cachedAuthor != null) append("\nby ${cachedAuthor}")
                append("\n\n")
            }
        }
        setStatus("${videoHeader}Starting processing… [${if (frameAccurate) "frame-accurate" else "fast"}]")

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

    /** Grey out / restore all interactive controls during processing. */
    private fun setProcessingUi(active: Boolean) {
        runOnUiThread {
            // Keep screen on while processing, release when done
            if (active) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val controls = listOf(retryButton, browseFileButton, openPlayerButton,
                pickFolderButton, idInput, radioFast, radioAccurate)
            for (v in controls) {
                v.isEnabled = !active
                v.alpha = if (active) 0.35f else 1f
            }
            cancelButton.visibility = if (active) View.VISIBLE else View.GONE
            progressBar.visibility = if (active) View.VISIBLE else View.GONE
            if (!active) {
                progressBar.progress = 0
                // Re-enable controls; retryButton state managed by updateCards()
                val nonRetry = listOf(browseFileButton, openPlayerButton, pickFolderButton,
                    idInput, radioFast, radioAccurate)
                for (v in nonRetry) { v.isEnabled = true; v.alpha = 1f }
                updateCards()
            }
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

    /** Fetch SponsorBlock segments for [videoId] in background; update UI when done. */
    private fun fetchSegmentsForId(videoId: String, force: Boolean = false) {
        if (videoId.length != 11) return
        if (!force && videoId == lastFetchedId) return
        lastFetchedId = videoId
        cachedSegments = null
        cachedTitle = null
        cachedAuthor = null
        runOnUiThread {
            modeHintView.visibility = View.GONE
            highlightRadio(radioFast, false)
            highlightRadio(radioAccurate, false)
            idCardState = CardState.CHECKING
            updateCards()
        }
        runOnUiThread {
            retryButton.isEnabled = false
            retryButton.alpha = 0.5f
            retryButton.text = "Checking SponsorBlock…"
        }
        Thread {
            // Fetch title and segments in parallel
            var title: String? = null
            var author: String? = null
            try {
                val url = java.net.URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    title = json.optString("title").ifBlank { null }
                    author = json.optString("author_name").ifBlank { null }
                }
            } catch (e: Exception) {
                Log.w(tag, "oEmbed title fetch failed: ${e.message}")
            }

            val segments = try {
                SponsorBlockClient().fetchRich(videoId)
            } catch (e: Exception) {
                Log.w(tag, "Segment pre-fetch failed: ${e.message}")
                null
            }
            cachedSegments = segments
            cachedTitle = title
            cachedAuthor = author
            runOnUiThread {
                idCardState = when {
                    segments == null -> CardState.OK   // network error — fail open
                    segments.isEmpty() -> CardState.ERROR
                    else -> CardState.OK
                }
                applySegmentUiState(videoId, segments, title, author)
                updateCards()
                applyModeRecommendation()
            }
        }.start()
    }

    private fun applySegmentUiState(videoId: String, segments: List<SponsorSegmentInfo>?,
                                    title: String? = null, author: String? = null) {
        val titleLine = if (title != null) "\"$title\"${if (author != null) "\nby $author" else ""}\n\n" else ""
        when {
            segments == null -> {
                retryButton.isEnabled = true
                retryButton.alpha = 1f
                retryButton.text = "Process with this ID"
                if (title != null) {
                    val currentStatus = statusView.text?.toString().orEmpty()
                    if (!currentStatus.contains(title)) statusView.text = titleLine + currentStatus
                }
            }
            segments.isEmpty() -> {
                retryButton.isEnabled = false
                retryButton.alpha = 0.4f
                retryButton.text = "No segments — cannot process"
                val currentStatus = statusView.text?.toString().orEmpty()
                val note = "\n\n⚠️ No SponsorBlock segments found for ID: $videoId"
                val base = if (title != null && !currentStatus.startsWith("\"")) titleLine + currentStatus else currentStatus
                if (!base.contains("No SponsorBlock segments")) statusView.text = base + note
            }
            else -> {
                retryButton.isEnabled = true
                retryButton.alpha = 1f
                retryButton.text = "Process with this ID"
                val summary = segments.joinToString("\n") {
                    "  [${it.category}] ${"%.1f".format(it.start)}s – ${"%.1f".format(it.end)}s (~${"%.0f".format(it.end - it.start)}s)"
                }
                val totalCut = segments.sumOf { it.end - it.start }
                val note = "🔍 ${segments.size} segment(s) to cut (~${"%.1f".format(totalCut)}s):\n$summary"
                // Replace full status with: title + note (clean, no stacking)
                val currentStatus = statusView.text?.toString().orEmpty()
                val baseStatus = when {
                    currentStatus.contains("SponsorBlock segment") || currentStatus.startsWith("✅") -> {
                        // strip old segment note, keep everything before it
                        val cut = currentStatus.indexOf("\n\n✅").takeIf { it >= 0 }
                            ?: currentStatus.indexOf("\n\n⚠️").takeIf { it >= 0 }
                            ?: currentStatus.indexOf("✅").takeIf { it == 0 }?.let { 0 }
                        if (cut != null && cut > 0) currentStatus.substring(0, cut) else ""
                    }
                    else -> currentStatus
                }
                statusView.text = (if (title != null) titleLine else "") +
                    (if (baseStatus.isNotBlank() && !baseStatus.startsWith("\"")) baseStatus + "\n\n" else "") +
                    note
            }
        }
    }

    private fun makeCard(p: Int, bgColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 3 / 4, p, p * 3 / 4)
            val margin = (8 * resources.displayMetrics.density).toInt()
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(bgColor)
                setStroke((1.5f * resources.displayMetrics.density).toInt(), 0x44FFFFFF)
            }
            background = shape
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(margin, margin / 2, margin, margin / 2)
            layoutParams = lp
        }
    }

    private fun cardColor(state: CardState): Int = when (state) {
        CardState.PENDING -> 0x44888888
        CardState.CHECKING -> 0x44CCAA00
        CardState.OK -> 0x4400AA44
        CardState.ERROR -> 0x44CC3333
    }

    private fun cardBorder(state: CardState): Int = when (state) {
        CardState.PENDING -> 0x88888888.toInt()
        CardState.CHECKING -> 0xFFCCAA00.toInt()
        CardState.OK -> 0xFF00CC44.toInt()
        CardState.ERROR -> 0xFFCC3333.toInt()
    }

    private fun cardIcon(state: CardState): String = when (state) {
        CardState.PENDING -> "⏳"
        CardState.CHECKING -> "⏳"
        CardState.OK -> "✅"
        CardState.ERROR -> "❌"
    }

    private fun applyCardState(card: LinearLayout, icon: TextView, state: CardState) {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            this.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(cardColor(state))
            setStroke((1.5f * resources.displayMetrics.density).toInt(), cardBorder(state))
        }
        card.background = shape
        icon.text = cardIcon(state)
    }

    /** Re-evaluate both card states and enable/disable the Process button accordingly. */
    private fun updateCards() {
        runOnUiThread {
            applyCardState(idCardView, idCardIcon, idCardState)
            applyCardState(fileCardView, fileCardIcon, fileCardState)
            val bothOk = idCardState == CardState.OK && fileCardState == CardState.OK
            retryButton.isEnabled = bothOk
            retryButton.alpha = if (bothOk) 1f else 0.5f
            retryButton.text = when {
                idCardState == CardState.CHECKING -> "Checking SponsorBlock…"
                idCardState == CardState.ERROR -> "No segments — cannot process"
                !bothOk -> "Process with this ID"
                else -> "▶ Process with this ID"
            }
        }
    }

    private fun applyModeRecommendation() {
        val uri = pendingUri ?: return
        Thread {
            // Use MediaMetadataRetriever for fast duration + mime check — no ffprobe needed
            var durationMs: Long? = null
            var isAudio = false
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(this, uri)
                durationMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val mime = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
                isAudio = mime.startsWith("audio/") ||
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes"
                mmr.release()
            } catch (e: Exception) {
                Log.w(tag, "MediaMetadataRetriever failed: ${e.message}")
            }

            val durationMin = if (durationMs != null) durationMs / 60000.0 else null

            val (hint, recommendFast) = when {
                isAudio ->
                    "💡 Fast recommended — audio files gain nothing from frame-accurate mode.\n" +
                    "Fast stream-copies with ~0.1s precision and results in a smaller file." to true
                durationMin != null && durationMin > 30 ->
                    "💡 Fast recommended — frame-accurate re-encodes the full video (1–3h on a phone).\n" +
                    "Fast mode takes under a minute, cuts within ~2s, and results in a smaller file." to true
                else -> "" to false
            }

            runOnUiThread {
                if (hint.isBlank()) {
                    modeHintView.visibility = View.GONE
                    highlightRadio(radioFast, false)
                    highlightRadio(radioAccurate, false)
                } else {
                    modeHintView.text = hint
                    modeHintView.visibility = View.VISIBLE
                    highlightRadio(radioFast, recommendFast)
                    highlightRadio(radioAccurate, !recommendFast)
                }
            }
        }.start()
    }

    private fun highlightRadio(button: RadioButton, recommended: Boolean) {
        if (recommended) {
            // Orange glow: rounded rectangle background
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFFF8C00.toInt())
                setColor(0x22FF8C00.toInt())
            }
            button.background = shape
            button.setTextColor(0xFFFF8C00.toInt())
        } else {
            button.background = null
            button.setTextColor(android.graphics.Color.WHITE.also {
                // Use default text color from theme
                val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
                button.setTextColor(ta.getColor(0, android.graphics.Color.WHITE))
                ta.recycle()
            })
        }
    }

    private fun startDotAnim(baseText: String) {
        stopDotAnim()  // always cancel previous before starting new
        dotBaseText = baseText
        dotPhaseIdx = 0
        val header = if (cachedTitle != null) {
            "\"${cachedTitle}\"" + (if (cachedAuthor != null) "\nby ${cachedAuthor}" else "") + "\n\n"
        } else ""
        val tick = object : Runnable {
            override fun run() {
                if (dotRunnable !== this) return  // stale runnable — bail out
                statusView.text = "$header$dotBaseText ${dotPhases[dotPhaseIdx]}"
                dotPhaseIdx = (dotPhaseIdx + 1) % dotPhases.size
                dotHandler.postDelayed(this, 500)
            }
        }
        dotRunnable = tick
        dotHandler.post(tick)
    }

    private fun stopDotAnim() {
        dotRunnable?.let { dotHandler.removeCallbacks(it) }
        dotRunnable = null
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            stopDotAnim()
            statusView.text = text
        }
    }

    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }
}


