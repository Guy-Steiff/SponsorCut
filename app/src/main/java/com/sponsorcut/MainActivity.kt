// app/src/main/java/com/sponsorcut/MainActivity.kt

package com.sponsorcut

import android.app.Activity
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.core.content.withStyledAttributes
import androidx.core.net.toUri
import androidx.core.view.isVisible
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import com.sponsorcut.core.model.ExecutionMode
import com.sponsorcut.core.model.StreamSelection
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var idInput: EditText
    private lateinit var retryButton: Button
    private lateinit var pickFolderButton: Button
    private lateinit var radioFast: RadioButton
    private lateinit var radioSwAccurate: RadioButton
    private lateinit var radioHwAccurate: RadioButton
    private lateinit var radioKeepBoth: RadioButton
    private lateinit var radioAudioOnly: RadioButton
    private lateinit var radioVideoOnly: RadioButton
    private lateinit var containerSpinner: Spinner
    private lateinit var videoCodecSpinner: Spinner
    private lateinit var audioCodecSpinner: Spinner
    private lateinit var audioContainerSpinner: Spinner
    private lateinit var videoSectionSpinner: Spinner
    private lateinit var audioSectionSpinner: Spinner
    private lateinit var qualityModeSpinner: Spinner
    private lateinit var crfSpinner: Spinner
    private lateinit var cqSpinner: Spinner
    private lateinit var bitrateInput: EditText
    private lateinit var presetSpinner: Spinner
    private lateinit var frameRateSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var audioSampleRateSpinner: Spinner
    private lateinit var audioBitrateInput: EditText
    private lateinit var audioBitratePresetSpinner: Spinner
    private lateinit var videoInputInfoView: TextView
    private lateinit var audioInputInfoView: TextView
    private lateinit var segmentCategoryContainer: LinearLayout
    private lateinit var segmentListContainer: LinearLayout

    private var encoderCatalog: List<EncoderOption> = emptyList()
    private var uiVideoCatalog: List<EncoderOption> = emptyList()
    private var uiAudioCatalog: List<EncoderOption> = emptyList()
    private var uiVideoChoices: List<EncoderOption?> = emptyList()
    private var uiAudioChoices: List<EncoderOption?> = emptyList()
    private var refreshOutputOptionsCallback: (() -> Unit)? = null
    private val segmentFetchHandler = Handler(Looper.getMainLooper())
    private var pendingSegmentFetchId: String? = null
    private var detectedCategories: List<String> = emptyList()
    private val dynamicCategoryCheckboxes = mutableMapOf<String, CheckBox>()
    private val selectedSegmentIndexes = mutableSetOf<Int>()
    private val segmentCheckboxes = mutableMapOf<Int, CheckBox>()
    private var currentSegments: List<SponsorSegmentInfo> = emptyList()
    private val invalidSegmentIndexes = mutableSetOf<Int>()
    private var hasManualSegmentEdits = false
    private var pendingSegmentSaveFormat: String? = null
    private lateinit var addSegmentButton: Button
    private lateinit var saveSegmentsTxtButton: Button
    private lateinit var saveSegmentsJsonButton: Button
    private lateinit var clearSummaryButton: Button
    private var suppressCategoryCallbacks = false
    private lateinit var streamGroup: RadioGroup
    private var manualStreamSelection: StreamSelection = StreamSelection.BOTH
    private var streamSelectionForced = false
    private var suppressStreamCallbacks = false
    private lateinit var streamOptionsLabel: TextView

    private var videoSectionCustom = false
    private var audioSectionCustom = false
    private var selectedVideoEncoder: String? = null
    private var selectedAudioEncoder: String? = null
    private var selectedAudioContainer: String? = null
    private var selectedContainer: String? = null
    private var qualityUsesCrf = false
    private var selectedCrf: Int = 23
    private var selectedCq: Int? = null
    private var selectedPreset: String = "medium"
    private var inputFrameRate: String? = null

    private val frameRateTable = DropdownTable(
        originalValues = listOf(
            "30",
            "23.976",
            "24",
            "25",
            "29.97",
            "50",
            "59.94",
            "60"
        ),
        normalize = { value ->
            val raw = value.trim()
            val match = Regex("\\d+(?:\\.\\d+)?").find(raw)
            val number = match?.value ?: raw
            if (number.contains('.')) {
                number.trimEnd('0').trimEnd('.')
            } else {
                number
            }
        }
    )

    private var frameRateRows: List<DropdownRow<String>> = emptyList()
    private var selectedFrameRate: String? = null
    private var selectedResolutionHeight: Int? = null
    private var selectedAudioSampleRate: Int? = null
    private var selectedAudioBitratePreset: String? = null
    private var detectedAudioBitrate: Long? = null
    private var audioBitrateFallbackActive = false
    private var suppressOutputCallbacks = false
    private lateinit var bitratePresetSpinner: Spinner
    private lateinit var outputConsequenceView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var browseFileButton: Button
    private lateinit var loadSegmentsButton: Button
    private lateinit var openPlayerButton: Button
    private lateinit var cancelButton: Button
    private lateinit var logButton: Button
    private lateinit var saveLogButton: Button
    private lateinit var logView: TextView
    private lateinit var donationView: LinearLayout
    private lateinit var modeHintView: TextView
    private lateinit var idCardView: LinearLayout
    private lateinit var idCardIcon: TextView
    private lateinit var idCardNote: TextView
    private lateinit var fileCardView: LinearLayout
    private lateinit var fileCardIcon: TextView
    private lateinit var fileCardLabel: TextView
    private lateinit var processWithoutSegmentsCheckBox: CheckBox

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
    private var cachedFileName: String? = null
    private val prefsName = "sponsorcut_prefs"
    private val keyLastVideoId = "last_video_id"
    private val keyLastVideoIdTs = "last_video_id_ts"
    private val keyOutputFolderUri = "output_folder_uri"
    private val keyFrameAccurate = "frame_accurate"
    private val keyHwAccurate = "hw_accurate"
    private val keyUseSegmentation = "use_segmentation"
    private val keyLastFileUri = "last_file_uri"
    private val keyLastFileName = "last_file_name"
    private val keyActiveProcessingId = "active_processing_id"
    private val keyActiveProcessingUri = "active_processing_uri"
    private val keyActiveProcessingFileName = "active_processing_file_name"
    private val keyActiveProcessingTitle = "active_processing_title"
    private val keyActiveProcessingAuthor = "active_processing_author"
    private val keyLastOutputFileName = "last_output_file_name"
    private val keyLastCompletedStatus = "last_completed_status"
    private val keyLastCompletedLog = "last_completed_log"
    private val recentIdMaxAgeMs = 7 * 24 * 60 * 60 * 1000L  // 7 days
    private val tag = "SponsorCut"
    private val pickFolderRequestCode = 42
    private val browseFileRequestCode = 43
    private val pickSegmentsRequestCode = 44
    private val saveSegmentsTxtRequestCode = 45
    private val saveSegmentsJsonRequestCode = 46

    private var localSegmentFileUri: Uri? = null

    // Prevents programmatic YouTube-ID updates from being treated as an
    // explicit user selection of SponsorBlock.
    private var suppressIdTextWatcher = false
    private var localSegmentFileName: String? = null
    private var localSegments: List<SponsorSegmentInfo>? = null
    private var localSegmentWarnings: List<SegmentWarning> = emptyList()
    private var localSegmentText: String? = null
    private var localSegmentsAwaitingDuration = false
    private var localSegmentsResolvedDurationSec: Double? = null
    private var suppressLocalSegmentReloadOnce = false

    private val categoryCheckboxes = mutableMapOf<String, CheckBox>()

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
                val finalStatus = if (cancelled) "⏹ Cancelled." else text
                saveCompletedRunState(finalStatus, DiagLog.get())
                clearActiveProcessingSnapshot()
                if (error && isUriAccessError(text)) {
                    pendingUri = null
                    fileCardState = CardState.PENDING
                    runOnUiThread {
                        fileCardLabel.text = getString(R.string.no_file_selected)
                        browseFileButton.text = getString(R.string.browse_video_file)
                    }
                }
                setStatus(finalStatus)
                runOnUiThread {
                    setProcessingUi(active = false)
                    logButton.visibility = View.VISIBLE
                    saveLogButton.visibility = View.VISIBLE
                    if (done && !error && !cancelled) {
                        donationView.visibility = View.VISIBLE
                    }
                }
            } else {
                runOnUiThread { startDotAnim(text) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show launch crashes even when statusView does not yet exist.
        val previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SponsorCutCRASH", "UNCAUGHT", throwable)

            val message = buildString {
                append(getString(R.string.launch_crash_heading))
                append("\n\n")
                append(throwable.javaClass.name)
                append("\n\n")
                append(throwable.message ?: getString(R.string.launch_crash_no_message))
                append("\n\n")
                append(throwable.stackTraceToString().take(4000))
            }

            try {
                DiagLog.append("LAUNCH CRASH", message)
            } catch (_: Throwable) {
            }

            try {
                runOnUiThread {
                    try {
                        setContentView(
                            TextView(this).apply {
                                text = message
                                textSize = 11f
                                typeface = android.graphics.Typeface.MONOSPACE
                                setTextIsSelectable(true)
                                setPadding(32, 32, 32, 32)
                                setBackgroundColor(0xFF220000.toInt())
                                setTextColor(0xFFFFCCCC.toInt())
                            }
                        )
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }

            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
            previousExceptionHandler?.uncaughtException(thread, throwable)
        }

        try {
            DiagLog.append("Launch", "MainActivity.onCreate entered")
            setupUi()
            DiagLog.append("Launch", "MainActivity.setupUi completed")
        } catch (e: Throwable) {
            DiagLog.append("Launch", "setupUi failed: ${e.stackTraceToString()}")
            throw e
        }
        restoreActiveProcessingSnapshotIfNeeded()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requested = mutableListOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            ).filter {
                checkSelfPermission(it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            if (requested.isNotEmpty()) {
                requestPermissions(requested.toTypedArray(), 99)
            }
        }

        // Initialize output-form availability after the complete UI exists.
        // With no inspected media, both Video and Audio forms remain disabled.
        updateStreamSelectionAvailability()

        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ProcessingService.BROADCAST_PROGRESS)
        ContextCompat.registerReceiver(
            this,
            progressReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (ProcessingService.isProcessingActive) {
            restoreActiveProcessingSnapshotIfNeeded()
            setProcessingUi(active = true)
        } else {
            // A completion, failure, or cancellation broadcast may have arrived
            // while this Activity was paused and its receiver was unregistered.
            // Reconcile the visible UI with the Service's actual state.
            clearActiveProcessingSnapshot()
            setProcessingUi(active = false)
            restoreCompletedRunStateIfNeeded()

            val savedInputUri = getSharedPreferences(prefsName, MODE_PRIVATE)
                .getString("sponsorcut_last_input_uri", null)
                ?.let { runCatching { it.toUri() }.getOrNull() }

            if (savedInputUri != null) {
                // Only restore a previous media selection if Android still has
                // usable access to it. Otherwise, clear the stale entry and show
                // the normal pending state.
                if (!canReadUri(savedInputUri)) {
                    pendingUri = null
                    fileCardState = CardState.PENDING
                    fileCardLabel.text = getString(R.string.no_file_selected)
                    browseFileButton.text = getString(R.string.browse_video_file)

                    getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                        remove("sponsorcut_last_input_uri")
                        remove(keyLastFileName)
                    }

                    updateCards()
                } else if (pendingUri == null) {
                    // A share intent can recreate this Activity. Restore the media
                    // selection before inspection; inspection alone does not set
                    // pendingUri or the file card.
                    pendingUri = savedInputUri
                    fileCardState = CardState.OK

                    val savedFileName = FileResolver.getDisplayName(this, savedInputUri)
                        .ifBlank {
                            savedInputUri.lastPathSegment ?: "previous file"
                        }

                    fileCardLabel.text = getString(R.string.file_name_with_icon, savedFileName)
                    browseFileButton.text = getString(R.string.change_file)
                    inspectSelectedUri(savedInputUri)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 99 &&
            pendingUri != null &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        ) {
            inspectSelectedUri(pendingUri!!)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(progressReceiver)
        } catch (_: Exception) {
        }
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
            getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                putString(keyOutputFolderUri, treeUri.toString())
            }
            val path = treeUri.lastPathSegment ?: treeUri.toString()
            setStatus(getString(R.string.output_folder_set, path))
            pickFolderButton.text = getString(R.string.output_folder_button, path)
        }
        if (
            (requestCode == saveSegmentsTxtRequestCode ||
                    requestCode == saveSegmentsJsonRequestCode) &&
            resultCode == RESULT_OK
        ) {
            val uri = data?.data ?: return
            val format = if (requestCode == saveSegmentsJsonRequestCode) "json" else "txt"
            val content = if (format == "json") {
                segmentsJsonExport(currentSegments)
            } else {
                segmentsTxtExport(currentSegments)
            }

            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(content.toByteArray())
                }
                setStatus(getString(R.string.segments_saved, uri))
            } catch (e: Exception) {
                setStatus(getString(R.string.segments_save_failed, e.message))
            }

            pendingSegmentSaveFormat = null
        }

        if (requestCode == pickSegmentsRequestCode && resultCode == RESULT_OK) {
            val segmentUri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    segmentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }

            clearLocalSegmentSource()

            localSegmentFileUri = segmentUri
            localSegmentFileName = FileResolver.getDisplayName(this, segmentUri)
                .ifBlank { segmentUri.lastPathSegment ?: "segments" }
            clearCompletedRunState()
            localSegments = null
            localSegmentWarnings = emptyList()
            cachedSegments = null
            cachedTitle = null
            cachedAuthor = null
            lastFetchedId = ""

            loadSegmentsButton.text = getString(
                R.string.file_name_with_icon,
                localSegmentFileName
            )
            setStatus(getString(R.string.segment_file_selected_parsing))
            loadLocalSegments(segmentUri, pendingVideoInfo?.durationSec)
        }

        if (requestCode == browseFileRequestCode && resultCode == RESULT_OK) {
            val fileUri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            pendingUri = fileUri
            fileCardState = CardState.OK
            clearCompletedRunState()
            val fileName = FileResolver.getDisplayName(this, fileUri)
                .ifBlank { fileUri.lastPathSegment ?: "file" }
            fileCardLabel.text = getString(R.string.file_name_with_icon, fileName)
            browseFileButton.text = getString(R.string.change_file)
            saveFileUri(fileUri, fileName)
            updateCards()
            inspectSelectedUri(fileUri)

            // Priority 1: whatever is already typed/shown in idInput (covers the URL-share→browse flow)
            val currentInput = idInput.text?.toString().orEmpty()
            val videoId = if (currentInput.length == 11 &&
                currentInput.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }
            ) {
                currentInput
            } else {
                extractId(currentInput)
            }
            // Do NOT fall back to the cache here — a stale cached ID from a different
            // session will silently corrupt the pairing. The user must provide the ID explicitly.

            setIdInputProgrammatically(videoId)
            idInput.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
            browseFileButton.visibility = View.VISIBLE
            if (videoId.isBlank())
                setStatus(getString(R.string.file_selected_no_youtube_id))
            else {
                setStatus(getString(R.string.file_selected_fetching_segments, videoId))
                fetchSegmentsForId(videoId, force = true)
            }
            applyModeRecommendation()
        }
    }

    private fun refreshFrameRateDropdown() {
        frameRateRows = frameRateTable.rowsForInput(
            inputValue = inputFrameRate
        )

        val selectedDisplay = frameRateTable.displayForRealValue(
            rows = frameRateRows,
            realValue = selectedFrameRate
        ) ?: frameRateRows.first().displayValue

        // Keep the selected value synchronized with the row actually shown.
        // Without this, a stale numeric frame rate can remain in
        // selectedFrameRate while the spinner visibly falls back to Input.
        selectedFrameRate = frameRateTable.realValueForDisplay(
            rows = frameRateRows,
            displayValue = selectedDisplay
        ) ?: selectedFrameRate

        setAdapterKeepingSelection(
            frameRateSpinner,
            frameRateRows.map { it.displayValue },
            selectedDisplay
        )
    }

    private fun setAdapterKeepingSelection(
        spinner: Spinner,
        values: List<String>,
        preferred: String?
    ) {
        val adapter = spinner.adapter
        val existing = if (adapter == null) {
            emptyList()
        } else {
            (0 until adapter.count).map { adapter.getItem(it)?.toString().orEmpty() }
        }
        if (existing == values) {
            val index = preferred?.let { values.indexOf(it) } ?: -1
            if (index >= 0 && spinner.selectedItemPosition != index) {
                spinner.setSelection(index, false)
            }
            return
        }

        suppressOutputCallbacks = true
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values
        )
        val index = preferred?.let { values.indexOf(it) } ?: -1
        spinner.setSelection(if (index >= 0) index else 0, false)
        suppressOutputCallbacks = false
    }

    private fun setupUi() {
        val p = (16 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            text = getString(R.string.share_downloaded_video)
            textSize = 15f
            setPadding(p, p, p, p)
            setTextIsSelectable(true)
        }

        idInput = EditText(this).apply {
            hint = getString(R.string.youtube_url_or_id_hint)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressIdTextWatcher) return

                    val raw = s?.toString().orEmpty()
                    val id =
                        if (raw.length == 11 && raw.all { it.isLetterOrDigit() || it == '_' || it == '-' })
                            raw else extractId(raw)

                    segmentFetchHandler.removeCallbacksAndMessages(null)

                    if (id.isBlank()) {
                        pendingSegmentFetchId = null
                        return
                    }

                    clearLocalSegmentSource()
                    updateCards()
                    statusView.text = getString(R.string.youtube_id_entered_source_discarded)

                    pendingSegmentFetchId = id
                    segmentFetchHandler.postDelayed({
                        val currentId = idInput.text?.toString().orEmpty()
                        val currentExtracted = if (
                            currentId.length == 11 &&
                            currentId.all { it.isLetterOrDigit() || it == '_' || it == '-' }
                        ) {
                            currentId
                        } else {
                            extractId(currentId)
                        }

                        if (currentExtracted == pendingSegmentFetchId) {
                            fetchSegmentsForId(
                                videoId = currentExtracted,
                                force = true,
                                replaceLocalSourceOnSuccess = true
                            )
                        }
                    }, 450)
                }
            })
        }

        retryButton = Button(this).apply {
            text = getString(R.string.process_with_id)
            isEnabled = false
            alpha = 0.5f
            setBackgroundColor(0xFF2E7D32.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                // Commit any in-progress timestamp/category edit before
                // validating segments or deciding whether processing can start.
                currentFocus?.clearFocus()

                val uri = pendingUri
                if (uri == null) {
                    setStatus(getString(R.string.no_shared_video_pending))
                    return@setOnClickListener
                }

                // A local media file can be processed without YouTube metadata.
                // A YouTube ID is required only when SponsorBlock segmentation
                // is enabled and no self-contained local/manual segments exist.
                val segmentationEnabled = processWithoutSegmentsCheckBox.isChecked

                if (!segmentationEnabled) {
                    startProcessing(uri, "")
                    return@setOnClickListener
                }

                // An open-ended manual segment means "through end of media".
                // If media duration is known, resolve it now instead of blocking
                // processing with "Load a media file...".
                val mediaDuration = pendingVideoInfo?.durationSec
                if (mediaDuration != null && currentSegments.any { it.endIsOpen }) {
                    val resolved = currentSegments.map { segment ->
                        if (segment.endIsOpen) {
                            segment.copy(end = mediaDuration, endIsOpen = false)
                        } else {
                            segment
                        }
                    }

                    currentSegments = resolved
                    hasManualSegmentEdits = true
                    renderSegmentList(resolved, preserveSelection = true)
                    updateCards()
                }

                // Local TXT/JSON segments and manually created segments are
                // self-contained: neither requires nor validates a YouTube ID.
                val hasManualSegments = hasManualSegmentEdits && currentSegments.isNotEmpty()

                if (localSegments != null || hasManualSegments) {
                    if (currentSegments.any { !isSegmentRowValid(it) }) {
                        setStatus(getString(R.string.invalid_segment_timestamps))
                        return@setOnClickListener
                    }

                    if (currentSegments.any { it.endIsOpen }) {
                        setStatus(
                            getString(R.string.resolve_open_ended_segment)
                        )
                        return@setOnClickListener
                    }

                    startProcessing(uri, "")
                    return@setOnClickListener
                }

                val raw = idInput.text?.toString().orEmpty()
                val videoId =
                    if (raw.length == 11 && raw.all { it.isLetterOrDigit() || it == '_' || it == '-' }) raw
                    else extractId(raw)
                if (videoId.isBlank()) {
                    setStatus(getString(R.string.invalid_youtube_url_or_id))
                    return@setOnClickListener
                }
                saveId(videoId)
                startProcessing(uri, videoId)
            }
        }

        val savedFolder =
            getSharedPreferences(prefsName, MODE_PRIVATE).getString(keyOutputFolderUri, null)
        val folderLabel = if (savedFolder != null)
            getString(
                R.string.output_folder_button,
                savedFolder.toUri().lastPathSegment ?: savedFolder
            )
        else
            getString(R.string.pick_output_folder_optional)
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
            text = getString(R.string.cancel_job)
            visibility = View.GONE
            setBackgroundColor(0xFFCC3333.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (!ProcessingService.isProcessingActive) {
                    clearActiveProcessingSnapshot()
                    setProcessingUi(active = false)
                    return@setOnClickListener
                }

                isEnabled = false
                alpha = 0.5f
                text = getString(R.string.cancelling)
                val cancelIntent = Intent(this@MainActivity, ProcessingService::class.java).apply {
                    action = ProcessingService.ACTION_CANCEL
                }
                startService(cancelIntent)
            }
        }

        browseFileButton = Button(this).apply {
            text = getString(R.string.browse_video_file)
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("video/*", "audio/*", "application/octet-stream")
                    )
                }
                startActivityForResult(i, browseFileRequestCode)
            }
        }

        // "Open player" button — just reminds user to copy URL; no longer launches player
        // because launching PipePipe/NewPipe via getLaunchIntentForPackage plays the video
        // rather than showing the URL to copy.
        openPlayerButton = Button(this).apply {
            text = getString(R.string.how_to_get_youtube_id)
            setOnClickListener {
                toast(getString(R.string.how_to_get_youtube_id_instructions))
            }
        }

        loadSegmentsButton = Button(this).apply {
            text = getString(R.string.load_txt_json_segments)
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("text/plain", "application/json", "application/octet-stream")
                    )
                }
                startActivityForResult(i, pickSegmentsRequestCode)
            }
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(statusView, lp)

            clearSummaryButton = Button(context).apply {
                text = getString(R.string.clear_summary)
                isEnabled = false
                alpha = 0.5f
                visibility = View.GONE
                setOnClickListener {
                    clearCompletedRunState()
                    statusView.text = ""
                    isEnabled = false
                    alpha = 0.5f
                    visibility = View.GONE
                }
            }
            addView(clearSummaryButton, lp)

            addView(progressBar, lp)
            addView(cancelButton, lp)

            // ── Provided segment categories ────────────────────────────────
            segmentCategoryContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(p * 2, p / 4, p, p / 4)
            }
            addView(segmentCategoryContainer, lp)

            // ── Individual segment rundown ─────────────────────────────
            segmentListContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(p * 2, p / 4, p, p / 4)
            }
            addView(segmentListContainer, lp)

            val segmentToolbar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
            }

            fun toolbarButton(label: String, onClick: () -> Unit): Button {
                return Button(context).apply {
                    text = label
                    gravity = android.view.Gravity.CENTER
                    isSingleLine = true
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { onClick() }
                }
            }

            addSegmentButton = toolbarButton("+ Add segment") {
                addManualSegment()
            }
            saveSegmentsTxtButton = toolbarButton("Save TXT") {
                beginSaveSegments("txt")
            }
            saveSegmentsJsonButton = toolbarButton("Save JSON") {
                beginSaveSegments("json")
            }
            segmentToolbar.addView(
                addSegmentButton, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            segmentToolbar.addView(
                saveSegmentsTxtButton, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            segmentToolbar.addView(
                saveSegmentsJsonButton, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            addView(segmentToolbar, lp)
            segmentListContainer.tag = segmentToolbar

            renderDetectedCategories()

            // ── Card 1: YouTube ID ────────────────────────────────────────────
            idCardView = makeCard(p, cardColor(CardState.PENDING)).also { card ->
                processWithoutSegmentsCheckBox = CheckBox(context).apply {
                    text = getString(R.string.use_segmentation)
                    isChecked = getSharedPreferences(prefsName, MODE_PRIVATE)
                        .getBoolean(keyUseSegmentation, true)
                    setOnCheckedChangeListener { _, checked ->
                        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                            putBoolean(keyUseSegmentation, checked)
                        }
                        updateCards()
                    }
                }
                idCardIcon = TextView(context).apply {
                    text = "⏳"
                    textSize = 20f
                    setPadding(0, 0, p / 2, 0)
                }
                idCardNote = TextView(context).apply {
                    text = ""
                    textSize = 12f
                    visibility = View.GONE
                }
                val idTopRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(
                        processWithoutSegmentsCheckBox, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        idCardIcon, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        idTopRow, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        idInput, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        openPlayerButton, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        loadSegmentsButton, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        idCardNote, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
                card.addView(
                    col, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
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
                    text = getString(R.string.no_file_selected)
                    textSize = 14f
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(
                        fileCardIcon, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        fileCardLabel, LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                    )
                }
                card.addView(
                    row, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                card.addView(
                    browseFileButton, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(fileCardView, lp)

            addView(retryButton, lp)
            addView(pickFolderButton, lp)

            // Cut mode selector
            val modeLabel = TextView(context).apply {
                text = getString(R.string.cut_mode)
                setPadding(p, p / 2, p, 0)
            }
            addView(modeLabel, lp)

            val radioGroup = RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(p, 0, p, 0)
            }
            val modePrefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val savedFrameAccurate = modePrefs.getBoolean(keyFrameAccurate, false)
            val savedHwAccurate = modePrefs.getBoolean(keyHwAccurate, false)
            radioFast = RadioButton(context).apply {
                id = View.generateViewId()
                text = getString(R.string.fast_mode_description)
                isChecked = !savedFrameAccurate && !savedHwAccurate
            }
            radioHwAccurate = RadioButton(context).apply {
                id = View.generateViewId()
                text = getString(R.string.hw_accurate_mode_description)
                isChecked = savedHwAccurate
            }
            radioSwAccurate = RadioButton(context).apply {
                id = View.generateViewId()
                text = getString(R.string.sw_accurate_mode_description)
                isChecked = savedFrameAccurate
            }
            radioGroup.addView(radioFast)
            radioGroup.addView(radioHwAccurate)
            radioGroup.addView(radioSwAccurate)

            val initialId = when {
                savedHwAccurate -> radioHwAccurate.id
                savedFrameAccurate -> radioSwAccurate.id
                else -> radioFast.id
            }
            radioGroup.check(initialId)
            radioGroup.setOnCheckedChangeListener { _, _ ->
                getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                    putBoolean(keyFrameAccurate, radioSwAccurate.isChecked)
                    putBoolean(keyHwAccurate, radioHwAccurate.isChecked)
                }
            }
            addView(radioGroup, lp)

            modeHintView = TextView(context).apply {
                visibility = View.GONE
                textSize = 13f
                setPadding(p, p / 2, p, p / 2)
            }
            addView(modeHintView, lp)

            // ── Processing options ─────────────────────────────────────────
            val streamLabel = TextView(context).apply {
                text = getString(R.string.output_streams)
                setPadding(p, p, p, 0)
                textSize = 14f
            }
            streamOptionsLabel = streamLabel
            addView(streamLabel, lp)

            streamGroup = RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(p * 2, 0, p, p / 2)
            }

            radioKeepBoth = RadioButton(context).apply {
                id = View.generateViewId()
                text = getString(R.string.keep_audio_and_video)
                isChecked = true
            }
            radioAudioOnly = RadioButton(context).apply {
                id = View.generateViewId()
                text = getString(R.string.audio_only)
            }
            radioVideoOnly = RadioButton(context).apply {
                id = View.generateViewId()
                text = getString(R.string.video_only)
            }

            streamGroup.addView(radioKeepBoth)
            streamGroup.addView(radioAudioOnly)
            streamGroup.addView(radioVideoOnly)
            streamGroup.check(radioKeepBoth.id)
            addView(streamGroup, lp)

            outputConsequenceView = TextView(context).apply {
                textSize = 12f
                setTextColor(0xFFCCCCCC.toInt())
                setPadding(p, p / 2, p, p)
            }
            addView(outputConsequenceView, lp)

            streamGroup.setOnCheckedChangeListener { _, _ ->
                if (suppressStreamCallbacks) return@setOnCheckedChangeListener

                manualStreamSelection = when {
                    radioAudioOnly.isChecked -> StreamSelection.AUDIO_ONLY
                    radioVideoOnly.isChecked -> StreamSelection.VIDEO_ONLY
                    else -> StreamSelection.BOTH
                }

                updateStreamSelectionAvailability()
            }

            // ── Output format controls ─────────────────────────────────────
            fun makeRow(label: String, control: View, annotation: TextView? = null): LinearLayout {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, p / 3, 0, p / 3)
                }

                val title = TextView(context).apply {
                    text = label
                    textSize = 13f
                    setPadding(0, 0, p / 2, 0)
                }
                row.addView(
                    title, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )

                row.addView(
                    control, LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                )

                annotation?.let {
                    it.textSize = 11f
                    it.alpha = 0.65f
                    it.setPadding(p / 2, 0, 0, 0)
                    row.addView(
                        it, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }

                return row
            }

            videoSectionSpinner = Spinner(context)
            audioSectionSpinner = Spinner(context)
            containerSpinner = Spinner(context)
            videoCodecSpinner = Spinner(context)
            audioCodecSpinner = Spinner(context)
            audioContainerSpinner = Spinner(context)
            qualityModeSpinner = Spinner(context)
            bitratePresetSpinner = Spinner(context)
            presetSpinner = Spinner(context)
            frameRateSpinner = Spinner(context)
            resolutionSpinner = Spinner(context)
            audioSampleRateSpinner = Spinner(context)
            audioBitrateInput = EditText(context).apply {
                hint = getString(R.string.audio_bitrate_hint)
            }

            audioBitratePresetSpinner = Spinner(context).apply {
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (suppressOutputCallbacks) return

                        val choice = parent?.getItemAtPosition(position)?.toString().orEmpty()
                        selectedAudioBitratePreset = choice

                        when {
                            choice == getString(R.string.custom) -> Unit
                            choice == getString(R.string.input_bitrate) -> detectedAudioBitrate?.let {
                                audioBitrateInput.setText(
                                    getString(
                                        R.string.audio_bitrate_with_unit,
                                        it / 1000
                                    )
                                )
                            }

                            choice == getString(R.string.audio_bitrate_128k) ||
                                    choice == getString(R.string.audio_bitrate_fallback) ->
                                audioBitrateInput.setText(getString(R.string.audio_bitrate_128k))

                            choice.endsWith("k") -> audioBitrateInput.setText(choice)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }

            crfSpinner = Spinner(context)
            cqSpinner = Spinner(context)
            bitrateInput = EditText(context).apply {
                hint = getString(R.string.video_bitrate_hint)
            }
            videoInputInfoView = TextView(context)
            audioInputInfoView = TextView(context)

            addView(makeRow(getString(R.string.video_label), videoSectionSpinner), lp)
            addView(videoInputInfoView, lp)
            addView(makeRow(getString(R.string.video_codec_label), videoCodecSpinner), lp)
            addView(makeRow(getString(R.string.container_label), containerSpinner), lp)
            addView(
                makeRow(
                    getString(R.string.quality_label),
                    qualityModeSpinner,
                    TextView(context)
                ), lp
            )

            val qualityValueRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(p * 2, 0, 0, 0)
            }
            qualityValueRow.addView(
                crfSpinner, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            qualityValueRow.addView(
                cqSpinner, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            qualityValueRow.addView(
                bitrateInput, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            qualityValueRow.addView(
                bitratePresetSpinner, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(qualityValueRow, lp)

            addView(makeRow(getString(R.string.preset_label), presetSpinner), lp)
            addView(
                makeRow(
                    getString(R.string.frame_rate_label),
                    frameRateSpinner,
                    TextView(context)
                ), lp
            )
            addView(makeRow(getString(R.string.resolution_label), resolutionSpinner), lp)

            addView(makeRow(getString(R.string.audio_label), audioSectionSpinner), lp)
            addView(audioInputInfoView, lp)
            addView(makeRow(getString(R.string.audio_codec_label), audioCodecSpinner), lp)
            addView(makeRow(getString(R.string.audio_container_label), audioContainerSpinner), lp)
            addView(makeRow(getString(R.string.sample_rate_label), audioSampleRateSpinner), lp)
            val audioBitrateValueRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(p * 2, 0, 0, 0)
            }

            audioBitrateValueRow.addView(
                audioBitrateInput, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            audioBitrateValueRow.addView(
                audioBitratePresetSpinner, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            addView(makeRow(getString(R.string.audio_bitrate_label), audioBitrateValueRow), lp)

            fun resolvedVideoChoiceLabel(mode: ExecutionMode): String {
                val inputFamily = EncoderCatalog.familyForInputCodec(pendingVideoInfo?.videoCodec)
                val matching = uiVideoCatalog.firstOrNull { it.codecFamily == inputFamily }

                if (matching != null) {
                    return "${matching.label} - input"
                }

                val fallback = when (mode) {
                    ExecutionMode.HW_ACCURATE ->
                        uiVideoCatalog.firstOrNull { it.name == "h264_mediacodec" }
                            ?: uiVideoCatalog.firstOrNull()

                    ExecutionMode.SW_ACCURATE ->
                        uiVideoCatalog.firstOrNull { it.name == "mpeg4" }
                            ?: uiVideoCatalog.firstOrNull()

                    else -> null
                }

                return fallback?.let { "${it.label} - fallback" }
                    ?: "No compatible video encoder"
            }

            fun resolvedAudioChoiceLabel(): String {
                val inputFamily = EncoderCatalog.familyForInputCodec(pendingVideoInfo?.audioCodec)
                val matching = uiAudioCatalog.firstOrNull { it.codecFamily == inputFamily }

                return if (matching != null) {
                    "${matching.label} - input"
                } else {
                    "AAC - fallback"
                }
            }

            fun resolvedContainerChoiceLabel(): String {
                val summary = pendingVideoInfo?.summaryLine ?: ""

                return when {
                    // MPEG-TS output is unsupported for transcoding.
                    // TS input is automatically planned as MP4 output.
                    summary.contains("MPEGTS", ignoreCase = true) -> "MP4 - input"
                    summary.contains("MOV,MP4", ignoreCase = true) -> "MP4 - input"
                    summary.contains("Matroska", ignoreCase = true) -> "MKV - input"
                    summary.contains("WebM", ignoreCase = true) -> "WebM - input"
                    summary.contains("AVI", ignoreCase = true) -> "AVI - input"
                    else -> "MP4 - fallback"
                }
            }

            fun refreshOutputOptions() {
                if (suppressOutputCallbacks) return

                val mode = when {
                    radioHwAccurate.isChecked -> ExecutionMode.HW_ACCURATE
                    radioSwAccurate.isChecked -> ExecutionMode.SW_ACCURATE
                    else -> ExecutionMode.FAST
                }

                val videoCatalog = EncoderCatalog.videoOptions(encoderCatalog, mode)
                    .filter { it.available }
                val audioCatalog = EncoderCatalog.audioOptions(encoderCatalog)
                    .filter { it.available }

                uiVideoCatalog = videoCatalog
                uiAudioCatalog = audioCatalog

                if (selectedVideoEncoder != null && uiVideoCatalog.none { it.name == selectedVideoEncoder }) {
                    selectedVideoEncoder = null
                    videoSectionCustom = false
                }

                if (selectedAudioEncoder != null && uiAudioCatalog.none { it.name == selectedAudioEncoder }) {
                    selectedAudioEncoder = null
                    audioSectionCustom = false
                }

                if (mode == ExecutionMode.FAST) {
                    videoSectionCustom = false
                    audioSectionCustom = false
                    selectedVideoEncoder = null
                    selectedAudioEncoder = null
                    selectedContainer = null
                }

                setAdapterKeepingSelection(
                    videoSectionSpinner,
                    listOf("Auto", "Custom"),
                    if (videoSectionCustom) "Custom" else "Auto"
                )
                setAdapterKeepingSelection(
                    audioSectionSpinner,
                    listOf("Auto", "Custom"),
                    if (audioSectionCustom) "Custom" else "Auto"
                )

                val resolvedVideoLabel = resolvedVideoChoiceLabel(mode)
                val resolvedVideoBase = resolvedVideoLabel.substringBefore(" - ")

                uiVideoChoices = listOf(null) + videoCatalog.filter {
                    it.label != resolvedVideoBase
                }

                val videoValues = if (mode == ExecutionMode.FAST) {
                    listOf("Copy input video")
                } else {
                    listOf(resolvedVideoLabel) + uiVideoChoices.drop(1).mapNotNull {
                        it?.label
                    }
                }
                val preferredVideo = if (videoSectionCustom && selectedVideoEncoder != null) {
                    videoCatalog.firstOrNull { it.name == selectedVideoEncoder }
                        ?.let { "${it.label}${if (it.available) "" else " — unavailable"}" }
                } else {
                    videoValues.firstOrNull()
                }
                setAdapterKeepingSelection(
                    videoCodecSpinner,
                    videoValues,
                    preferredVideo
                )

                val resolvedAudioLabel = resolvedAudioChoiceLabel()
                val resolvedAudioBase = resolvedAudioLabel.substringBefore(" - ")

                uiAudioChoices = listOf(null) + audioCatalog.filter {
                    it.label != resolvedAudioBase
                }

                val audioValues = if (mode == ExecutionMode.FAST) {
                    listOf("Copy compatible audio")
                } else {
                    listOf(resolvedAudioLabel) + uiAudioChoices.drop(1).mapNotNull {
                        it?.label
                    }
                }
                val preferredAudio = if (audioSectionCustom && selectedAudioEncoder != null) {
                    audioCatalog.firstOrNull { it.name == selectedAudioEncoder }
                        ?.let { "${it.label}${if (it.available) "" else " — unavailable"}" }
                } else null
                setAdapterKeepingSelection(audioCodecSpinner, audioValues, preferredAudio)

                val effectiveAudioName = selectedAudioEncoder
                    ?: uiAudioCatalog.firstOrNull {
                        it.codecFamily == EncoderCatalog.familyForInputCodec(
                            pendingVideoInfo?.audioCodec
                        )
                    }?.name

                val effectiveAudioFamily = effectiveAudioName?.let {
                    EncoderCatalog.find(encoderCatalog, it)?.codecFamily
                } ?: EncoderCatalog.familyForInputCodec(pendingVideoInfo?.audioCodec)


                val inputAudioCodecForContainer =
                    pendingVideoInfo?.audioCodec?.lowercase().orEmpty()

                val resolvedAudioContainerLabel = when {
                    inputAudioCodecForContainer.contains("aac") -> "M4A - input"
                    inputAudioCodecForContainer.contains("mp3") -> "MP3 - input"
                    inputAudioCodecForContainer.contains("opus") -> "OPUS - input"
                    inputAudioCodecForContainer.contains("vorbis") -> "OGG - input"
                    else -> "M4A - fallback"
                }

                val resolvedAudioContainerBase =
                    resolvedAudioContainerLabel.substringBefore(" - ")

                val audioContainerOptions = when (effectiveAudioFamily) {
                    "AAC" -> listOf("M4A", "ADTS")
                    "MP3" -> listOf("MP3")
                    "Opus" -> listOf("OPUS", "OGG")
                    "Vorbis" -> listOf("OGG")
                    else -> listOf("M4A", "MKV")
                }

                val audioContainerValues =
                    listOf(resolvedAudioContainerLabel) + audioContainerOptions.filter {
                        it != resolvedAudioContainerBase
                    }

                setAdapterKeepingSelection(
                    audioContainerSpinner,
                    audioContainerValues,
                    selectedAudioContainer?.replaceFirstChar { it.uppercase() }
                )

                val effectiveVideoEncoder = effectiveVideoEncoderName(mode)

                val selectedVideo = effectiveVideoEncoder?.let { name ->
                    videoCatalog.firstOrNull { it.name == name }
                }

                val supportsCrf = selectedVideo?.name in setOf(
                    "libx264",
                    "libx265"
                )

                val qualityLabel = when (selectedVideo?.name) {
                    "hevc_mediacodec" -> "CQ"
                    else -> "CRF"
                }

                setAdapterKeepingSelection(
                    qualityModeSpinner,
                    if (supportsCrf) listOf(qualityLabel, "Bitrate") else listOf("Bitrate"),
                    if (qualityUsesCrf && supportsCrf) qualityLabel else "Bitrate"
                )

                val isSoftwareX264X265 = selectedVideo?.name in setOf(
                    "libx264",
                    "libx265"
                )

                val isMediaCodec = selectedVideo?.name in setOf(
                    "h264_mediacodec",
                    "hevc_mediacodec"
                )

                crfSpinner.visibility = if (
                    supportsCrf && qualityUsesCrf && isSoftwareX264X265
                ) View.VISIBLE else View.GONE

                cqSpinner.visibility = if (
                    supportsCrf && qualityUsesCrf && isMediaCodec
                ) View.VISIBLE else View.GONE

                bitrateInput.visibility = if (supportsCrf && qualityUsesCrf) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

                bitratePresetSpinner.visibility = bitrateInput.visibility

                if (isSoftwareX264X265) {
                    setAdapterKeepingSelection(
                        crfSpinner,
                        (0..51).map { it.toString() },
                        selectedCrf.toString()
                    )
                }

                if (supportsCrf && isMediaCodec && selectedVideo != null) {
                    val capability = mediaCodecCqCapability(selectedVideo.codecFamily)

                    val cqChoices = if (capability.supported) {
                        (capability.min..capability.max).map { it.toString() }
                    } else {
                        listOf("CQ unavailable on this device")
                    }

                    val initialCq = selectedCq?.toString() ?: cqChoices.first()
                    selectedCq = initialCq.toIntOrNull()

                    setAdapterKeepingSelection(
                        cqSpinner,
                        cqChoices,
                        initialCq
                    )
                }

                setAdapterKeepingSelection(
                    bitratePresetSpinner,
                    listOf("Input", "1M", "2M", "4M", "8M", "12M", "Custom"),
                    "Input"
                )

                setAdapterKeepingSelection(
                    presetSpinner,
                    listOf(
                        "ultrafast", "superfast", "veryfast", "faster", "fast",
                        "medium", "slow", "slower", "veryslow", "placebo"
                    ),
                    selectedPreset
                )

                presetSpinner.isEnabled = isSoftwareX264X265 &&
                        mode == ExecutionMode.SW_ACCURATE
                presetSpinner.alpha = if (presetSpinner.isEnabled) 1f else 0.4f

                val probeFps = pendingVideoInfo?.fps
                val frameRateInputValue = probeFps?.let { fps ->
                    if (fps == fps.toInt().toFloat()) {
                        fps.toInt().toString()
                    } else {
                        "%.2f".format(fps)
                    }
                }

                inputFrameRate = frameRateInputValue
//                selectedFrameRate = null
                refreshFrameRateDropdown()

                val inputHeight = pendingVideoInfo?.height
                val allHeights = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
                val availableHeights = inputHeight?.let { height ->
                    allHeights.filter { it <= height }
                } ?: allHeights

                val resolutionInputValue = inputHeight?.let { "${it}p" }
                val resolutionInputLabel = resolutionInputValue?.let { "$it - input" }
                    ?: "Unknown - input"

                val resolutionChoices = listOf(resolutionInputLabel) + availableHeights.map {
                    "${it}p"
                }.filter { it != resolutionInputValue }

                setAdapterKeepingSelection(
                    resolutionSpinner,
                    resolutionChoices,
                    selectedResolutionHeight?.let { selected ->
                        val selectedLabel = "${selected}p"
                        resolutionChoices.firstOrNull {
                            it == selectedLabel || it.startsWith("$selectedLabel - input")
                        }
                    } ?: resolutionInputLabel
                )

                val probeSampleRate = pendingVideoInfo?.sampleRate
                val sampleRateInputValue = probeSampleRate?.toString()
                val sampleRateInputLabel = sampleRateInputValue?.let { "$it Hz - input" }
                    ?: "Unknown - input"

                val sampleRateChoices = listOf(sampleRateInputLabel) + listOf(
                    44100, 48000
                ).map { it.toString() }.filter { it != sampleRateInputValue }

                setAdapterKeepingSelection(
                    audioSampleRateSpinner,
                    sampleRateChoices,
                    selectedAudioSampleRate?.let { selected ->
                        val selectedLabel = selected.toString()
                        sampleRateChoices.firstOrNull {
                            it == selectedLabel || it.startsWith("$selectedLabel - input")
                        }
                    } ?: sampleRateInputLabel
                )

                // Clear obsolete TS/M2TS selections saved by older builds.
                if (selectedContainer?.lowercase() in setOf("ts", "m2ts")) {
                    selectedContainer = null
                }

                val resolvedContainerLabel = resolvedContainerChoiceLabel()
                val resolvedContainerBase = resolvedContainerLabel.substringBefore(" - ")

                val containerInputSummary = pendingVideoInfo?.summaryLine ?: ""
                val inputIsMp4Family = containerInputSummary.contains("MOV,MP4", ignoreCase = true)
                val mp4Label = if (inputIsMp4Family) "MP4 (input)" else "MP4"

                fun containerChoices(vararg options: String): List<String> {
                    return listOf(resolvedContainerLabel) + options.filter {
                        it.substringBefore(" (") != resolvedContainerBase
                    }
                }

                // Container choices must follow the effective output encoder,
                // not merely the input codec family. MPEG-TS output is currently
                // unsupported for transcoding; TS inputs are automatically planned
                // as MP4 output. TS preservation will be re-examined after the
                // FFmpegKitNext upgrade.
                val effectiveEncoderName = effectiveVideoEncoderName(mode)
                val effectiveVideoFamily = effectiveEncoderName?.let { encoderName ->
                    EncoderCatalog.find(encoderCatalog, encoderName)?.codecFamily
                }

                val containerValues = when {
                    mode == ExecutionMode.FAST -> listOf(resolvedContainerLabel)
                    effectiveVideoFamily == "H.264" ->
                        containerChoices(mp4Label, "MKV", "MOV", "AVI")

                    effectiveVideoFamily == "H.265/HEVC" ->
                        containerChoices(mp4Label, "MKV", "MOV")

                    effectiveVideoFamily == "VP9" ->
                        containerChoices("WebM", "MKV")

                    effectiveVideoFamily == "AV1" ->
                        containerChoices(mp4Label, "MKV", "WebM")

                    else -> containerChoices(mp4Label, "MKV", "AVI")
                }
                setAdapterKeepingSelection(
                    containerSpinner,
                    containerValues,
                    selectedContainer?.replaceFirstChar { it.uppercase() }
                )

                val inputSummary = pendingVideoInfo?.summaryLine ?: "unknown"
                val inputAudioCodec = pendingVideoInfo?.audioCodec ?: "unknown"
                val inputSampleRate = pendingVideoInfo?.sampleRate
                    ?.let { "$it Hz" } ?: "unknown"
                val inputAudioBitrate = pendingVideoInfo?.audioBitrate
                    ?.let { "${it / 1000} kbps" } ?: "unknown"

                videoInputInfoView.text = getString(R.string.input_summary, inputSummary)
                audioInputInfoView.text = getString(
                    R.string.audio_input_summary,
                    inputAudioCodec,
                    inputSampleRate,
                    inputAudioBitrate
                )

                // The fallback marker reflects the inspected source bitrate.
                // Do not derive it from the text field: after applying the
                // 128 kbps fallback, the text field is intentionally 128k.
                val audioBitrateFallback = audioBitrateFallbackActive

                if (audioBitrateFallback && selectedAudioBitratePreset == null) {
                    selectedAudioBitratePreset = getString(R.string.audio_bitrate_fallback)
                }

                val audioBitrateOptions = buildList {
                    add(getString(R.string.input_bitrate))
                    add("40k")
                    add("60k")
                    add("80k")
                    add("100k")
                    add("128k")
                    add("150k")
                    add(getString(R.string.custom))
                    if (audioBitrateFallback) {
                        add(getString(R.string.audio_bitrate_fallback))
                    }
                }

                val preferredAudioBitrate = selectedAudioBitratePreset
                    ?.takeIf { it in audioBitrateOptions }
                    ?: if (audioBitrateFallback) {
                        getString(R.string.audio_bitrate_fallback)
                    } else {
                        getString(R.string.input_bitrate)
                    }

                setAdapterKeepingSelection(
                    audioBitratePresetSpinner,
                    audioBitrateOptions,
                    preferredAudioBitrate
                )

                outputConsequenceView.text = getString(
                    R.string.output_consequence,
                    getString(
                        when (mode) {
                            ExecutionMode.FAST -> R.string.fast_stream_copy
                            ExecutionMode.SW_ACCURATE -> R.string.sw_accurate_processing
                            ExecutionMode.HW_ACCURATE -> R.string.hw_accurate_processing
                        }
                    ),
                    getString(
                        when {
                            radioAudioOnly.isChecked -> R.string.audio_only_output
                            radioVideoOnly.isChecked -> R.string.video_only_output
                            else -> R.string.both_streams
                        }
                    ),
                    getString(R.string.remove_selected_provided_segments)
                )
            }

            videoSectionSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        videoSectionCustom = position == 1
                        if (!videoSectionCustom) {
                            selectedVideoEncoder = null
                            selectedContainer = null
                        }
                        refreshOutputOptions()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            audioSectionSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        audioSectionCustom = position == 1

                        if (!audioSectionCustom) {
                            selectedAudioEncoder = null
                        } else if (selectedAudioEncoder == null) {
                            // The Auto row may display a resolved fallback such as
                            // "AAC - fallback". Make that effective choice real
                            // when the user switches to Custom.
                            selectedAudioEncoder = uiAudioCatalog.firstOrNull {
                                it.codecFamily == EncoderCatalog.familyForInputCodec(
                                    pendingVideoInfo?.audioCodec
                                )
                            }?.name ?: "aac"
                        }

                        refreshOutputOptions()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            videoCodecSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks || position <= 0) return

                        val option = uiVideoChoices
                            .getOrNull(position)

                        if (option == null) {
                            selectedVideoEncoder = null
                            videoSectionCustom = false
                        } else {
                            selectedVideoEncoder = option.name
                            videoSectionCustom = true
                            DiagLog.append("UI", "video codec selected: ${option.name}")
                        }

                        selectedContainer = null
                        refreshOutputOptions()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            audioCodecSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks || position <= 0) return

                        val option = uiAudioChoices
                            .getOrNull(position)

                        if (option == null) {
                            selectedAudioEncoder = null
                            audioSectionCustom = false
                        } else {
                            selectedAudioEncoder = option.name
                            audioSectionCustom = true
                            DiagLog.append("UI", "audio codec selected: ${option.name}")
                        }

                        refreshOutputOptions()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            audioContainerSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return

                        selectedAudioContainer = if (position == 0) {
                            null
                        } else {
                            audioContainerSpinner.selectedItem
                                ?.toString()
                                ?.substringBefore(" (")
                                ?.lowercase()
                        }

                        refreshOutputOptions()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            containerSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        selectedContainer = if (position == 0) {
                            null
                        } else {
                            containerSpinner.selectedItem
                                ?.toString()
                                ?.substringBefore(" (")
                                ?.lowercase()
                        }
                        if (selectedContainer != null) {
                            videoSectionCustom = true
                        }
                        refreshOutputOptions()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            resolutionSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        val selected = resolutionSpinner.selectedItem?.toString() ?: return
                        selectedResolutionHeight = Regex("^(\\d+)p$").find(selected)
                            ?.groupValues?.get(1)?.toInt()
                        refreshOutputOptions()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            qualityModeSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        qualityUsesCrf = qualityModeSpinner.selectedItem in setOf("CRF", "CQ")
                        refreshOutputOptionsCallback = { refreshOutputOptions() }
                        refreshOutputOptions()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            cqSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return

                        val value = cqSpinner.selectedItem
                            ?.toString()
                            ?.toIntOrNull()

                        if (selectedCq != value) {
                            selectedCq = value
                            refreshOutputOptions()
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            bitratePresetSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        val selected = bitratePresetSpinner.selectedItem?.toString() ?: return
                        if (selected == "Custom") return

                        if (selected == "Input") {
                            val bps = pendingVideoInfo?.videoBitrate
                            bitrateInput.setText(
                                if (bps != null) "${bps / 1000}k" else ""
                            )
                        } else {
                            bitrateInput.setText(selected.lowercase())
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            presetSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        selectedPreset = presetSpinner.selectedItem?.toString() ?: "medium"
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            frameRateSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        selectedFrameRate = frameRateTable.realValueForDisplay(
                            rows = frameRateRows,
                            displayValue = frameRateSpinner.selectedItem as? String
                        )
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            audioSampleRateSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        if (suppressOutputCallbacks) return
                        selectedAudioSampleRate = audioSampleRateSpinner.selectedItem
                            ?.toString()
                            ?.substringBefore(" (")
                            ?.toIntOrNull()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            refreshOutputOptions()

            radioGroup.setOnCheckedChangeListener { _, _ ->
                getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                    putBoolean(keyFrameAccurate, radioSwAccurate.isChecked)
                    putBoolean(keyHwAccurate, radioHwAccurate.isChecked)
                }
                refreshOutputOptions()
            }

            // ── Merge files placeholder ────────────────────────────────────
//            mergeCard = makeCard(p, 0x44666666).apply {
//                val title = TextView(context).apply {
//                    text = "🔗 Merge files"
//                    textSize = 15f
//                }
//                val subtitle = TextView(context).apply {
//                    text = "Combine multiple media files into one output. Coming soon."
//                    textSize = 12f
//                    alpha = 0.75f
//                }
//                val button = Button(context).apply {
//                    text = "Choose files"
//                    isEnabled = false
//                    alpha = 0.45f
//                }
//                addView(title)
//                addView(subtitle)
//                addView(button)
//            }
//            addView(mergeCard, lp)

            radioGroup.setOnCheckedChangeListener { _, _ ->
                getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                    putBoolean(keyFrameAccurate, radioSwAccurate.isChecked)
                    putBoolean(keyHwAccurate, radioHwAccurate.isChecked)
                }
                refreshOutputOptions()
            }

            // Signature
            val sig = TextView(context).apply {
                val url = "https://megacut.onrender.com/"
                val textContent = "By: Guy Steiff\nAbout: $url"

                val spannable = SpannableString(textContent)

                val start = textContent.indexOf(url)
                val end = start + url.length

                spannable.setSpan(
                    URLSpan(url),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                text = spannable
                movementMethod = LinkMovementMethod.getInstance()

                textSize = 12f
                setPadding(p, p * 2, p, p)
                alpha = 0.5f
            }
            addView(sig, lp)


            // Diagnostic log — hidden until a run completes
            logButton = Button(context).apply {
                text = getString(R.string.show_diagnostic_log)
                visibility = View.GONE
                setOnClickListener {
                    val showing = logView.isVisible
                    logView.isVisible = !showing
                    text = getString(
                        if (showing) R.string.show_diagnostic_log
                        else R.string.hide_diagnostic_log
                    )
                    if (!showing) logView.text = DiagLog.get().ifBlank { "(log is empty)" }
                }
            }
            addView(logButton, lp)

            saveLogButton = Button(context).apply {
                text = getString(R.string.save_diagnostic_log)
                visibility = View.GONE
                setOnClickListener {
                    if (ProcessingService.isProcessingActive) {
                        return@setOnClickListener
                    }
                    saveDiagnosticLog()
                }
            }
            addView(saveLogButton, lp)

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

            // ── Donation footer — hidden until a successful job completes ──────
            donationView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(p, p, p, p)
                setBackgroundColor(0xFF1A1A2E.toInt())

                val title = TextView(context).apply {
                    text = getString(R.string.donation_title)
                    textSize = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(0, 0, 0, p / 2)
                }
                addView(
                    title, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )

                val subtitle = TextView(context).apply {
                    text = getString(R.string.donation_subtitle)
                    textSize = 12f
                    setTextColor(0xFFCCCCCC.toInt())
                    setPadding(0, 0, 0, p)
                }
                addView(
                    subtitle, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )

                val coffeeBtn = Button(context).apply {
                    text = getString(R.string.buy_me_a_coffee)
                    setOnClickListener {
                        val i = Intent(
                            Intent.ACTION_VIEW,
                            "https://buymeacoffee.com/steiff".toUri()
                        )
                        startActivity(i)
                    }
                }
                addView(
                    coffeeBtn, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )

                val btcAddress = "bc1qqz6dxwc38lrhmp6u9880alkvretwv2d6ds5had"
                val btcUri = "bitcoin:$btcAddress?label=SponsorCut%20Donation"

                val btcLabel = TextView(context).apply {
                    text = getString(R.string.bitcoin_address, btcAddress)
                    textSize = 11f
                    setTextColor(0xFFAAAAAA.toInt())
                    setTextIsSelectable(true)
                    setPadding(0, p, 0, p / 4)
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                addView(
                    btcLabel, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )

                val btcPayBtn = Button(context).apply {
                    text = getString(R.string.donate_with_bitcoin)
                    setOnClickListener {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            btcUri.toUri()
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                        try {
                            startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    getString(R.string.bitcoin_address_copied),
                                    btcAddress
                                )
                            )
                            Toast.makeText(
                                context,
                                getString(R.string.bitcoin_wallet_missing_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                addView(
                    btcPayBtn, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(donationView, lp)
        }

        val scroll = ScrollView(this).apply {
            addView(scrollContent)
        }

        setContentView(scroll)
    }

    private var pendingVideoInfo: FFProbeInspector.VideoInfo? = null

    private fun renderDetectedCategories() {
        segmentCategoryContainer.removeAllViews()
        dynamicCategoryCheckboxes.clear()

        if (detectedCategories.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_segment_categories_detected)
                textSize = 12f
                alpha = 0.7f
            }
            segmentCategoryContainer.addView(empty)
            return
        }

        val title = TextView(this).apply {
            text = getString(R.string.detected_categories)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        segmentCategoryContainer.addView(title)

        val counts = currentSegments
            .groupBy { it.category }
            .mapValues { it.value.size }

        for (category in detectedCategories) {
            val checkbox = CheckBox(this).apply {
                text = getString(
                    R.string.category_count,
                    categoryLabel(category),
                    counts[category] ?: 0
                )
                isChecked = true
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressCategoryCallbacks) return@setOnCheckedChangeListener

                    currentSegments.forEachIndexed { index, segment ->
                        if (segment.category == category) {
                            segmentCheckboxes[index]?.isChecked = isChecked
                            if (isChecked) {
                                selectedSegmentIndexes.add(index)
                            } else {
                                selectedSegmentIndexes.remove(index)
                            }
                        }
                    }
                }
            }
            dynamicCategoryCheckboxes[category] = checkbox
            segmentCategoryContainer.addView(checkbox)
        }
    }

    private fun categoryLabel(category: String): String = when (category) {
        "sponsor" -> "Sponsor"
        "selfpromo" -> "Self-promotion"
        "interaction" -> "Interaction reminder"
        "intro" -> "Intro / intermission"
        "outro" -> "Outro / end cards"
        "preview" -> "Preview / recap"
        "filler" -> "Filler / tangent"
        "music_offtopic" -> "Non-music section"
        "poi_highlight" -> "Highlight"
        "exclusive_access" -> "Members-only access"
        "chapter" -> "Chapter"
        else -> category.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private data class MediaCodecCqCapability(
        val supported: Boolean,
        val min: Int,
        val max: Int
    )

    private fun mediaCodecCqCapability(codecFamily: String): MediaCodecCqCapability {
        val mime = when (codecFamily) {
            "H.264" -> "video/avc"
            "H.265/HEVC" -> "video/hevc"
            else -> return MediaCodecCqCapability(false, 0, 0)
        }

        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                if (codecInfo.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue

                val capabilities = codecInfo.getCapabilitiesForType(mime)
                val encoderCapabilities = capabilities.encoderCapabilities ?: continue

                val supportsCq = encoderCapabilities.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                )

                if (supportsCq) {
                    // EncoderCapabilities.getQualityRange() requires API 28.
                    // Older Android versions do not expose a quality range, so
                    // CQ cannot be safely offered there.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        return MediaCodecCqCapability(false, 0, 0)
                    }

                    val range = encoderCapabilities.qualityRange
                    return MediaCodecCqCapability(
                        supported = true,
                        min = range.lower,
                        max = range.upper
                    )
                }
            }

            MediaCodecCqCapability(false, 0, 0)
        } catch (e: Exception) {
            DiagLog.append(
                "CQCapability",
                "$codecFamily CQ probe failed: ${e.message}"
            )
            MediaCodecCqCapability(false, 0, 0)
        }
    }

    private fun effectiveVideoEncoderName(mode: ExecutionMode): String? {
        val info = pendingVideoInfo ?: return null

        val preferences = OutputPreferences(
            videoSectionMode = if (videoSectionCustom) {
                SectionMode.CUSTOM
            } else {
                SectionMode.AUTO
            },
            videoCodec = selectedVideoEncoder,
            qualityMode = if (qualityUsesCrf) {
                QualityMode.CRF
            } else {
                QualityMode.BITRATE
            }
        )

        return TranscodePolicy.resolveVideoEncoderForUi(
            info = info,
            mode = mode,
            preferences = preferences,
            catalog = encoderCatalog,
            inputFamily = EncoderCatalog.familyForInputCodec(info.videoCodec)
        )
    }

    private fun currentOutputPreferences(): OutputPreferences {
        val mode = when {
            radioHwAccurate.isChecked -> ExecutionMode.HW_ACCURATE
            radioSwAccurate.isChecked -> ExecutionMode.SW_ACCURATE
            else -> ExecutionMode.FAST
        }

        val effectiveEncoder = effectiveVideoEncoderName(mode)

        val supportsCrf = effectiveEncoder in setOf(
            "libx264",
            "libx265"
        )

        return OutputPreferences(
            videoSectionMode = if (videoSectionCustom) SectionMode.CUSTOM else SectionMode.AUTO,
            audioSectionMode = if (audioSectionCustom) SectionMode.CUSTOM else SectionMode.AUTO,
            videoCodec = selectedVideoEncoder,
            audioCodec = selectedAudioEncoder,
            container = selectedContainer,
            audioContainer = if (radioAudioOnly.isChecked) selectedAudioContainer else null,
            qualityMode = if (supportsCrf && qualityUsesCrf) {
                QualityMode.CRF
            } else {
                QualityMode.BITRATE
            },
            crf = if (effectiveEncoder in setOf("libx264", "libx265")) {
                selectedCrf
            } else {
                null
            },
            cq = null,
            videoBitrate = parseBitrate(bitrateInput.text?.toString()),
            preset = if (presetSpinner.isEnabled) selectedPreset else null,
            videoFrameRate = selectedFrameRate,
            audioSampleRate = selectedAudioSampleRate,
            audioBitrate = parseBitrate(audioBitrateInput.text?.toString()),
            videoMaxHeight = selectedResolutionHeight
        )
    }

    private fun inspectSelectedUri(uri: Uri) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            putString("sponsorcut_last_input_uri", uri.toString())
        }

        Thread {
            val info = try {
                val cached = FileResolver.uriToFile(this, uri)
                FFProbeInspector.inspect(cached)
            } catch (e: Exception) {
                DiagLog.append("TaskUI", "input inspection failed: ${e.message}")
                setStatus("Media inspection failed:\n${e.message}")
                null
            }

            runOnUiThread {
                pendingVideoInfo = info

                // Discover encoders now that we know an input is present.
                encoderCatalog = EncoderCatalog.discover()

                detectedAudioBitrate = info?.audioBitrate
                selectedAudioBitratePreset = null

                val audioBitrate = info?.audioBitrate
                audioBitrateFallbackActive =
                    audioBitrate == null || audioBitrate < 32000

                if (audioBitrateFallbackActive) {
                    // Telegram-related files can expose implausibly low audio
                    // bitrate metadata. Do not inherit it for re-encoding.
                    audioBitrateInput.setText(getString(R.string.audio_bitrate_128k))
                    selectedAudioBitratePreset = getString(R.string.audio_bitrate_fallback)
                } else {
                    audioBitrate?.let {
                        audioBitrateInput.setText(
                            getString(R.string.audio_bitrate_with_unit, it / 1000)
                        )
                    }
                }

                // Temporary post-inspection fault isolation:
                // identify which UI rebuild stage crashes without losing the
                // stack trace to an activity-level exception.
                try {
                    // Rebuild every output spinner using the real input and encoder
                    // catalog. This must occur after the audio-bitrate fallback so
                    // the dropdown immediately reflects "128k - fallback".
                    refreshOutputOptionsCallback?.invoke()
                } catch (e: Throwable) {
                    DiagLog.append(
                        "TaskUI",
                        "POST-INSPECT FAILURE in refreshOutputOptions:\n" +
                                e.stackTraceToString()
                    )
                    setStatus(
                        getString(R.string.media_loaded_output_options_failed, e.message)
                    )
                }

                try {
                    // Local TXT/JSON segments require the actual media duration for
                    // "end" resolution and duration clamping.
                    loadLocalSegmentsIfNeeded()
                } catch (e: Throwable) {
                    DiagLog.append(
                        "TaskUI",
                        "POST-INSPECT FAILURE in loadLocalSegmentsIfNeeded:\n" +
                                e.stackTraceToString()
                    )
                    setStatus(
                        getString(R.string.media_loaded_segment_reload_failed, e.message)
                    )
                }

                try {
                    updateStreamSelectionAvailability()
                } catch (e: Throwable) {
                    DiagLog.append(
                        "TaskUI",
                        "POST-INSPECT FAILURE in updateStreamSelectionAvailability:\n" +
                                e.stackTraceToString()
                    )
                    setStatus(
                        getString(R.string.media_loaded_stream_ui_failed, e.message)
                    )
                }

                val inputSummary = info?.summaryLine ?: "unknown"
                val inputAudioCodec = info?.audioCodec ?: "unknown"
                val inputSampleRate = info?.sampleRate?.let { "$it Hz" } ?: "unknown"
                val inputAudioBitrate = info?.audioBitrate
                    ?.let { "${it / 1000} kbps" } ?: "unknown"

                videoInputInfoView.text = getString(R.string.input_summary, inputSummary)
                audioInputInfoView.text = getString(
                    R.string.audio_input_summary,
                    inputAudioCodec,
                    inputSampleRate,
                    inputAudioBitrate
                )

                // Show the input bitrate immediately when Quality is set to Input.
                // Prefer the video stream's own bitrate. If ffprobe cannot provide
                // one, estimate it as container bitrate minus audio bitrate, but
                // only when both values are present and internally consistent.
                val detectedVideoBitrate = info?.videoBitrate
                    ?: info?.containerBitrate?.let { container ->
                        val audio = info.audioBitrate
                        if (audio != null && container > audio) {
                            container - audio
                        } else {
                            container
                        }
                    }

                detectedVideoBitrate?.let {
                    bitrateInput.setText(
                        getString(R.string.audio_bitrate_with_unit, it / 1000)
                    )
                }

                // Audio-bitrate fallback was applied before the first
                // output-options refresh, so the dropdown and text field already
                // reflect the effective encoding target.
            }
        }.start()
    }

    private fun loadLocalSegmentsIfNeeded() {
        if (suppressLocalSegmentReloadOnce) {
            suppressLocalSegmentReloadOnce = false
            return
        }

        val uri = localSegmentFileUri ?: return

        val duration = pendingVideoInfo?.durationSec

        // Keep parsed segments when the same media duration is still active.
        // Reparse when they were provisional, or when the user switches to a
        // different media file with a different duration.
        if (
            localSegments != null &&
            !localSegmentsAwaitingDuration &&
            duration == localSegmentsResolvedDurationSec
        ) {
            return
        }

        loadLocalSegments(uri, duration)
    }

    private fun loadLocalSegments(uri: Uri, duration: Double?) {
        val fileName = localSegmentFileName ?: "segment file"

        Thread {
            try {
                val text = try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: throw SegmentParseException(
                        code = "segment_file_read_failed",
                        message = "Cannot open segment file: $fileName"
                    )
                } catch (e: Exception) {
                    localSegmentText ?: throw e
                }

                localSegmentText = text

                // Before media is available, parse provisionally with an
                // effectively unbounded duration. This exposes categories and
                // checkboxes immediately; media inspection later reparses with
                // the real duration.
                val parseDuration = duration ?: Double.MAX_VALUE

                val parsed = SegmentParser.parseSegmentFile(
                    text = text,
                    duration = parseDuration,
                    reference = fileName
                )

                // With real media, an explicit "end" becomes ffprobe's media
                // duration. Without media, retain it as an unresolved marker.
                val result = if (duration == null) {
                    parsed
                } else {
                    parsed.copy(
                        segments = parsed.segments.map { segment ->
                            if (segment.endIsOpen) {
                                segment.copy(
                                    end = duration,
                                    endIsOpen = false
                                )
                            } else {
                                segment
                            }
                        }
                    )
                }

                runOnUiThread {
                    clearYouTubeIdSource()

                    localSegments = result.segments
                    localSegmentWarnings = result.warnings
                    localSegmentsAwaitingDuration = duration == null
                    localSegmentsResolvedDurationSec = duration
                    cachedSegments = result.segments
                    cachedTitle = null
                    cachedAuthor = null
                    idCardState = CardState.OK

                    updateCategoryAvailability(result.segments)
                    applySegmentUiState(
                        videoId = "",
                        segments = result.segments
                    )
                    updateCards()
                    updateStreamSelectionAvailability()

                    val unresolvedEndCount = result.segments.count { it.endIsOpen }
                    val totalCut = result.segments
                        .filter { !it.endIsOpen }
                        .sumOf { it.end - it.start }
                    val warningCount = result.warnings.size
                    val durationPending = duration == null
                    setStatus(
                        (if (durationPending) {
                            "Local segments loaded ✓ (waiting for media duration)\n"
                        } else {
                            "Local segments loaded ✓\n"
                        }) +
                                "File: $fileName\n" +
                                "${result.segments.size} segment(s)" +
                                (if (!durationPending) {
                                    ", removing ~${formatSegmentTimestamp(totalCut)}."
                                } else {
                                    "."
                                }) + "\n" +
                                (if (unresolvedEndCount > 0) {
                                    "$unresolvedEndCount unresolved \"end\" segment(s) — load media to resolve timing.\n"
                                } else {
                                    ""
                                }) +
                                (if (warningCount > 0) "$warningCount warning(s)." else "No warnings.")
                    )
                }
            } catch (e: Exception) {
                localSegments = null
                localSegmentWarnings = emptyList()
                cachedSegments = null
                idCardState = CardState.ERROR

                runOnUiThread {
                    updateCards()

                    val parseError = e as? SegmentParseException
                    val details = parseError?.details
                        .orEmpty()
                        .filter { it.isNotBlank() }
                        .joinToString("\n") { "• $it" }

                    setStatus(
                        "❌ Segment file failed\n" +
                                "$fileName\n\n" +
                                (e.message ?: e.toString()) +
                                (if (details.isNotBlank()) "\n\n$details" else "") +
                                "\n\nPlease correct the segment file and try again."
                    )
                }
            }
        }.start()
    }

    private fun segmentSourceLabel(videoId: String): String {
        if (hasManualSegmentEdits) return "ID: Manual"

        return when {
            localSegmentFileUri != null -> {
                val name = localSegmentFileName ?: "segment file"
                "Segmentation file TXT/JSON: $name (${localSegmentFileUri})"
            }

            videoId.isNotBlank() -> "YouTube: https://youtu.be/$videoId"
            else -> "ID: Manual"
        }
    }

    private fun localSegmentsJson(
        segments: List<SponsorSegmentInfo>? = null
    ): String? {
        // The visible, editable segment list is the authoritative processing
        // payload. Callers may pass an already-selected subset; otherwise use
        // the full current editable list, falling back to a parsed local file.
        val effectiveSegments: List<SponsorSegmentInfo> = segments
            ?: currentSegments.ifEmpty { localSegments.orEmpty() }

        if (effectiveSegments.isEmpty()) return null

        val array = org.json.JSONArray()
        for ((category, start, end) in effectiveSegments) {
            val obj = org.json.JSONObject()
            obj.put("category", category)
            obj.put("start", start)
            obj.put("end", end)
            array.put(obj)
        }

        return array.toString()
    }

    private fun parseBitrate(raw: String?): Long? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isBlank() || value == "auto") return null
        val match = Regex("^(\\d+(?:\\.\\d+)?)([kKmM]?)$").find(value) ?: return null
        val amount = match.groupValues[1].toDouble()
        val multiplier = when (match.groupValues[2]) {
            "k" -> 1000.0
            "m" -> 1_000_000.0
            else -> 1.0
        }
        return (amount * multiplier).toLong()
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            "com.sponsorcut.PROCESS_FILE" -> handleDirectIntent(intent)
            Intent.ACTION_SEND -> handleShareIntent(intent)
            Intent.ACTION_VIEW -> handleViewIntent(intent)
            else -> {
                // While a job is active, never run cold-open restore/fetch logic.
                // This avoids re-fetching segments when returning from background.
                if (ProcessingService.isProcessingActive) {
                    restoreActiveProcessingSnapshotIfNeeded()
                    setProcessingUi(active = true)
                    return
                }

                // A launcher intent may arrive while an existing session already
                // has media and a usable segment source. Preserve its status and
                // do not invite the user to add another media file.
                val sessionAlreadyReady =
                    pendingUri != null &&
                            (localSegments != null || idCardState == CardState.OK)

                if (sessionAlreadyReady) {
                    return
                }

                // Cold open — restore last remembered ID into the input field if recent enough
                val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                val savedId = prefs.getString(keyLastVideoId, "").orEmpty()
                val savedTs = prefs.getLong(keyLastVideoIdTs, 0L)
                val isRecent = System.currentTimeMillis() - savedTs <= recentIdMaxAgeMs
                if (savedId.isNotBlank() && isRecent) {
                    setIdInputProgrammatically(savedId)
                    idCardState = CardState.CHECKING
                    updateCards()
                    restoreLastFileUri()
                    setStatus("Welcome back — last ID: $savedId\n\nShare or browse a video file to process it.")
                    fetchSegmentsForId(savedId)
                } else {
                    restoreLastFileUri()
                    setStatus("Paste a YouTube URL or ID above, then browse for your video file.")
                }
            }
        }
    }

    // Called by PipePipe/NewPipe fork with both URI and video ID pre-populated
    private fun handleDirectIntent(intent: Intent) {
        if (rejectIfBusy()) return
        val uriString = intent.getStringExtra("uri")
        val videoId = intent.getStringExtra("video_id").orEmpty()

        if (uriString.isNullOrBlank()) {
            setStatus(getString(R.string.direct_intent_missing_uri))
            return
        }
        val uri = uriString.toUri()
        pendingUri = uri
        fileCardState = CardState.OK
        clearCompletedRunState()
        runOnUiThread {
            fileCardLabel.text = getString(
                R.string.file_name_with_icon,
                uri.lastPathSegment ?: uriString
            )
            browseFileButton.text = getString(R.string.change_file)
        }
        inspectSelectedUri(uri)

        if (videoId.length == 11) {
            clearLocalSegmentSource()
            setIdInputProgrammatically(videoId)
            idCardState = CardState.CHECKING
            updateCards()
            setStatus("PipePipe/NewPipe ✓\nFetching segments for ID: $videoId")
            fetchSegmentsForId(videoId)
        } else {
            setStatus("PipePipe/NewPipe ✓\nNo video ID provided — paste it in the field above.")
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getUriExtra(name: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Uri::class.java)
        } else {
            getParcelableExtra(name)
        }

    private fun handleShareIntent(intent: Intent) {
        if (rejectIfBusy()) return
        Log.d(
            tag, "handleShareIntent: action=${intent.action} type=${intent.type} " +
                    "EXTRA_TEXT=${intent.getStringExtra(Intent.EXTRA_TEXT)?.take(80)} " +
                    "EXTRA_STREAM=${intent.getUriExtra(Intent.EXTRA_STREAM)}"
        )
        val rawUri = intent.getUriExtra(Intent.EXTRA_STREAM)
            ?: intent.clipData?.getItemAt(0)?.uri

        // Discard share-sheet image previews (e.g. android_share_sheet_image_preview.jpg)
        val uri = if (rawUri != null && isImageUri(rawUri)) null else rawUri

        if (uri != null) {
            val sharedFileName = FileResolver.getDisplayName(this, uri)
                .ifBlank { uri.lastPathSegment ?: "file" }
            val sharedExtension = sharedFileName.substringAfterLast('.', "").lowercase()
            val sharedMimeType = intent.type?.lowercase()

            val isSegmentFile =
                sharedExtension == "txt" ||
                        sharedExtension == "json" ||
                        sharedMimeType == "text/plain" ||
                        sharedMimeType == "application/json"

            if (isSegmentFile) {
                handleSharedSegmentFile(uri, sharedFileName)
                return
            }
        }

        if (uri == null) {
            val videoId = extractIdFromIntent(intent, Uri.EMPTY)
            if (videoId.isNotBlank()) {
                saveId(videoId)  // save now so file-share later will find it
                runOnUiThread { setIdInputProgrammatically(videoId) }
                idCardState = CardState.CHECKING
                updateCards()
                // A shared YouTube URL is an explicit SponsorBlock selection.
                // Do not reparse the current TXT/JSON share URI; replace it only
                // if SponsorBlock successfully returns usable segments.
                clearLocalSegmentSource()
                suppressLocalSegmentReloadOnce = true
                restoreLastFileUri()
                setStatus("✓ YouTube ID captured: $videoId\n\nNow browse to your downloaded video file.")
                fetchSegmentsForId(
                    videoId = videoId,
                    force = true,
                    replaceLocalSourceOnSuccess = true
                )
                return
            }
            setStatus("No video file detected. Share a downloaded video file.")
            return
        }

        pendingUri = uri
        fileCardState = CardState.OK
        clearCompletedRunState()
        inspectSelectedUri(uri)
        val sharedFileName =
            FileResolver.getDisplayName(this, uri).ifBlank { uri.lastPathSegment ?: "file" }
        runOnUiThread {
            fileCardLabel.text = getString(R.string.file_name_with_icon, sharedFileName)
            browseFileButton.text = getString(R.string.change_file)
        }
        saveFileUri(uri, sharedFileName)
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
            Log.i(
                tag,
                "Cache lookup: savedId='$savedId' savedTs=$savedTs ageMs=$ageMs maxAgeMs=$recentIdMaxAgeMs"
            )
            if (savedId.isNotBlank() && ageMs <= recentIdMaxAgeMs) {
                videoId = savedId
                idSource = "memory"
            }
        }

        Log.i(tag, "Detected id='$videoId' source=$idSource uri=$uri")

        // Persist any ID we found so it survives Activity recreation (e.g. switching to file manager)
        if (videoId.isNotBlank()) saveId(videoId)

        setIdInputProgrammatically(videoId)
        if (videoId.isBlank()) {
            setStatus("No YouTube ID detected.\nPaste a URL/ID in the field above.")
        } else {
            val sourceLabel =
                if (idSource == "memory") "⚠️ from memory — verify!" else "source: $idSource"
            setStatus("ID: $videoId ($sourceLabel)\nFetching title and segments…")
            idCardState = CardState.CHECKING
            updateCards()
            fetchSegmentsForId(videoId, force = true)
            applyModeRecommendation()
        }
    }

    private fun handleViewIntent(intent: Intent) {
        setStatus("Open-with intent received.\nURI: ${intent.data}\nType: ${intent.type}")
        if (rejectIfBusy()) return

        val uri = intent.data ?: run {
            setStatus("Open-with intent received, but no file URI was provided.")
            return
        }

        setStatus("Open-with file selected:\n$uri")
        pendingUri = uri
        fileCardState = CardState.OK
        clearCompletedRunState()
        inspectSelectedUri(uri)

        val fileName = FileResolver.getDisplayName(this, uri)
            .ifBlank { uri.lastPathSegment ?: "file" }

        runOnUiThread {
            fileCardLabel.text = getString(R.string.file_name_with_icon, fileName)
            browseFileButton.text = getString(R.string.change_file)
        }

        saveFileUri(uri, fileName)
        updateCards()
    }

    private fun handleSharedSegmentFile(uri: Uri, fileName: String) {
        clearYouTubeIdSource()
        clearLocalSegmentSource()

        localSegmentFileUri = uri
        localSegmentFileName = fileName
        clearCompletedRunState()
        localSegments = null
        localSegmentWarnings = emptyList()
        cachedSegments = null
        cachedTitle = null
        cachedAuthor = null
        idCardState = CardState.PENDING

        loadSegmentsButton.text = getString(R.string.file_name_with_icon, fileName)
        setStatus(getString(R.string.segments_shared_parsing, fileName))
        updateCards()
        loadLocalSegments(uri, pendingVideoInfo?.durationSec)
    }

    private fun setIdInputProgrammatically(value: String) {
        suppressIdTextWatcher = true
        idInput.setText(value)
        suppressIdTextWatcher = false
    }

    private fun clearLocalSegmentSource() {
        // Release persisted access when the TXT/JSON was selected in-app.
        // Share-intent URIs may not have persistable grants; ignore that.
        localSegmentFileUri?.let { uri ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        localSegmentFileUri = null
        localSegmentFileName = null
        localSegments = null
        localSegmentWarnings = emptyList()
        localSegmentText = null
        localSegmentsAwaitingDuration = false
        localSegmentsResolvedDurationSec = null
        loadSegmentsButton.text = getString(R.string.load_txt_json_segments)

        cachedSegments = null
        currentSegments = emptyList()
        hasManualSegmentEdits = false
        selectedSegmentIndexes.clear()
        segmentCheckboxes.clear()
        segmentCategoryContainer.removeAllViews()
        segmentListContainer.removeAllViews()
    }

    private fun clearYouTubeIdSource() {
        setIdInputProgrammatically("")
        pendingSegmentFetchId = null
        segmentFetchHandler.removeCallbacksAndMessages(null)
        lastFetchedId = ""
        cachedSegments = null
        cachedTitle = null
        cachedAuthor = null
        idCardState = CardState.PENDING

        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            remove(keyLastVideoId)
            remove(keyLastVideoIdTs)
        }
    }

    private fun isAudioOnlyContainer(uri: Uri?): Boolean {
        if (uri == null) return false

        val mime = runCatching { contentResolver.getType(uri) }
            .getOrNull()
            ?.lowercase()

        val audioMimePrefixes = listOf(
            "audio/mpeg",
            "audio/mp3",
            "audio/mp4",
            "audio/x-m4a",
            "audio/m4a",
            "audio/aac",
            "audio/opus",
            "audio/ogg",
            "audio/vorbis",
            "audio/flac",
            "audio/x-flac",
            "audio/wav",
            "audio/x-wav",
            "audio/x-aiff",
            "audio/aiff",
            "audio/x-ms-wma",
            "audio/amr",
            "audio/x-caf"
        )

        if (mime != null && audioMimePrefixes.any { mime.startsWith(it) }) {
            return true
        }

        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()

        return extension in setOf(
            "mp3",
            "m4a",
            "m4b",
            "m4r",
            "aac",
            "opus",
            "oga",
            "ogg",
            "wav",
            "flac",
            "alac",
            "aiff",
            "aif",
            "wma",
            "amr",
            "caf"
        )
    }

    private fun isAudioOnlyJob(
        uri: Uri?,
        info: FFProbeInspector.VideoInfo?
    ): Boolean {
        val videoCodec = info?.videoCodec?.trim()?.lowercase()
        val audioCodec = info?.audioCodec?.trim()?.lowercase()

        val hasAudio = !audioCodec.isNullOrBlank() && audioCodec != "unknown"
        val hasVideo = !videoCodec.isNullOrBlank() && videoCodec != "unknown"

        // FFprobe is authoritative when it finds both usable streams. Do not
        // let a container/URI heuristic force a video+audio file into
        // permanent Audio-only mode.
        if (hasVideo && hasAudio) return false

        if (isAudioOnlyContainer(uri)) return true

        if (info == null) return false

        if (hasAudio) return true

        val extension = uri?.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()

        val isMp3 = extension == "mp3" ||
                info.summaryLine.contains("mp3", ignoreCase = true)

        return isMp3 && videoCodec == "mjpeg"
    }

    private fun updateStreamSelectionAvailability() {
        val info = pendingVideoInfo

        val audioOnly = isAudioOnlyJob(pendingUri, info)

        val videoCodec = info?.videoCodec?.trim()?.lowercase()
        val audioCodec = info?.audioCodec?.trim()?.lowercase()

        val hasAudio = !audioCodec.isNullOrBlank() && audioCodec != "unknown"
        val hasVideo = !audioOnly &&
                !videoCodec.isNullOrBlank() &&
                videoCodec != "unknown"

        val videoOnly = hasVideo && !hasAudio

        DiagLog.append(
            "StreamUI",
            "videoCodec=$videoCodec audioCodec=$audioCodec " +
                    "hasVideo=$hasVideo hasAudio=$hasAudio " +
                    "audioOnly=$audioOnly videoOnly=$videoOnly"
        )

        applyStreamAwareUiState(
            audioOnly = audioOnly,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            videoOnly = videoOnly
        )
    }


    private fun applyStreamAwareUiState(
        hasVideo: Boolean,
        hasAudio: Boolean,
        audioOnly: Boolean,
        videoOnly: Boolean
    ) {
        val processingActive = ProcessingService.isProcessingActive
        // A successful FFprobe inspection is the authoritative condition for
        // enabling output controls. File-card state can be updated separately
        // and must not prevent otherwise valid media from being configured.
        val mediaLoaded = pendingVideoInfo != null

        fun setFormState(views: List<View>, enabled: Boolean) {
            fun apply(view: View) {
                view.isEnabled = enabled
                view.alpha = if (enabled) 1f else 0.35f

                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        apply(view.getChildAt(index))
                    }
                }
            }

            views.forEach(::apply)
        }

        fun row(view: View): View = view.parent as View

        fun commonAncestor(a: View, b: View): ViewGroup? {
            var left: View? = a
            while (left != null) {
                var right: View? = b
                while (right != null) {
                    if (left === right) return left as? ViewGroup
                    right = right.parent as? View
                }
                left = left.parent as? View
            }
            return null
        }

        commonAncestor(
            row(videoSectionSpinner),
            row(audioSectionSpinner)
        ) ?: return

        val videoViews = listOf(
            row(videoSectionSpinner),
            videoInputInfoView,
            row(videoCodecSpinner),
            row(containerSpinner),
            row(qualityModeSpinner),
            row(crfSpinner),
            row(presetSpinner),
            row(frameRateSpinner),
            row(resolutionSpinner)
        )

        val audioViews = listOf(
            row(audioSectionSpinner),
            audioInputInfoView,
            row(audioCodecSpinner),
            row(audioContainerSpinner),
            row(audioSampleRateSpinner),
            row(audioBitrateInput)
        )

        val streamChoiceForced = audioOnly || videoOnly

        // Until ffprobe has inspected the media, do not let the user select a
        // stream mode. Content URIs often hide the real file extension, so an
        // audio-only file cannot be identified reliably before inspection.
        val inspectionPending = pendingVideoInfo == null && !audioOnly
        val streamControlsEnabled =
            !processingActive && !streamChoiceForced && !inspectionPending

        // TEMPORARY DIAGNOSTIC: identify why output forms remain disabled.
//        val gateMessage = buildString {
//            append("Form gate: ")
//            append("mediaLoaded=$mediaLoaded, ")
//            append("hasVideo=$hasVideo, ")
//            append("hasAudio=$hasAudio, ")
//            append("processingActive=$processingActive, ")
//            append("audioOnly=$audioOnly, ")
//            append("videoOnly=$videoOnly, ")
//            append("manualStream=$manualStreamSelection, ")
//            append("videoCodec=${pendingVideoInfo?.videoCodec}, ")
//            append("audioCodec=${pendingVideoInfo?.audioCodec}")
//        }
//        DiagLog.append("FormGate", gateMessage)
//        setStatus(gateMessage)

        radioKeepBoth.isEnabled = streamControlsEnabled
        radioAudioOnly.isEnabled = streamControlsEnabled
        radioVideoOnly.isEnabled = streamControlsEnabled

        suppressStreamCallbacks = true

        if (audioOnly) {
            streamOptionsLabel.text =
                getString(R.string.output_streams_audio_only_unavailable)
            manualStreamSelection = StreamSelection.AUDIO_ONLY
            streamSelectionForced = true
            streamGroup.check(radioAudioOnly.id)
        } else if (videoOnly) {
            streamOptionsLabel.text =
                getString(R.string.output_streams_video_only_unavailable)
            manualStreamSelection = StreamSelection.VIDEO_ONLY
            streamSelectionForced = true
            streamGroup.check(radioVideoOnly.id)
        } else if (inspectionPending) {
            streamOptionsLabel.text = getString(R.string.output_streams_detecting)

            when (manualStreamSelection) {
                StreamSelection.AUDIO_ONLY -> streamGroup.check(radioAudioOnly.id)
                StreamSelection.VIDEO_ONLY -> streamGroup.check(radioVideoOnly.id)
                StreamSelection.BOTH -> streamGroup.check(radioKeepBoth.id)
            }
        } else {
            streamOptionsLabel.text = getString(R.string.output_streams_both)

            // A prior audio-only or video-only file may have forced the stream
            // selection. Restore the normal default when the newly loaded file
            // supports both streams; afterward, preserve explicit user choice.
            if (streamSelectionForced) {
                manualStreamSelection = StreamSelection.BOTH
                streamSelectionForced = false
            }

            when (manualStreamSelection) {
                StreamSelection.AUDIO_ONLY -> streamGroup.check(radioAudioOnly.id)
                StreamSelection.VIDEO_ONLY -> streamGroup.check(radioVideoOnly.id)
                StreamSelection.BOTH -> streamGroup.check(radioKeepBoth.id)
            }
        }

        suppressStreamCallbacks = false

        val selectedAudioOnly = audioOnly ||
                (!videoOnly && manualStreamSelection == StreamSelection.AUDIO_ONLY)

        val selectedVideoOnly = videoOnly ||
                (!audioOnly && manualStreamSelection == StreamSelection.VIDEO_ONLY)

        // Do not reparent or reorder forms. The original layout already places
        // Video before Audio. Hiding the irrelevant Video form for a strict
        // audio file removes the buffer naturally and avoids orphaned rows.
        if (selectedAudioOnly) {
            videoViews.forEach { it.visibility = View.GONE }
            audioViews.forEach { it.visibility = View.VISIBLE }

            setFormState(audioViews, mediaLoaded && hasAudio && !processingActive)
            setFormState(videoViews, false)
        } else {
            videoViews.forEach { it.visibility = View.VISIBLE }
            audioViews.forEach { it.visibility = View.VISIBLE }

            if (selectedVideoOnly) {
                setFormState(videoViews, mediaLoaded && hasVideo && !processingActive)
                setFormState(audioViews, false)
            } else {
                setFormState(videoViews, mediaLoaded && hasVideo && !processingActive)
                setFormState(audioViews, mediaLoaded && hasAudio && !processingActive)
            }
        }

        // The Audio container is meaningful only when the output is audio-only.
        // It must remain disabled for Keep-both and Video-only jobs.
        val audioContainerEnabled = selectedAudioOnly && !processingActive
        audioContainerSpinner.isEnabled = audioContainerEnabled
        audioContainerSpinner.alpha = if (audioContainerEnabled) 1f else 0.35f
    }

    private fun startProcessing(uri: Uri, videoId: String) {
        if (!canReadUri(uri)) {
            pendingUri = null
            fileCardState = CardState.PENDING
            runOnUiThread {
                fileCardLabel.text = getString(R.string.no_file_selected)
                browseFileButton.text = getString(R.string.browse_video_file)
                setStatus(getString(R.string.file_access_expired))
                updateCards()
            }
            return
        }

        val outputFolderUri = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(keyOutputFolderUri, null)
        val frameAccurate = radioSwAccurate.isChecked
        val removeSegments = processWithoutSegmentsCheckBox.isChecked
        val streamSelection = when {
            radioAudioOnly.isChecked -> StreamSelection.AUDIO_ONLY
            radioVideoOnly.isChecked -> StreamSelection.VIDEO_ONLY
            else -> StreamSelection.BOTH
        }
        val activeFileName = runCatching { FileResolver.getDisplayName(this, uri) }
            .getOrElse { uri.lastPathSegment ?: "file" }
        cachedFileName = activeFileName
        saveActiveProcessingSnapshot(
            videoId = videoId,
            uri = uri,
            fileName = activeFileName,
            title = cachedTitle,
            author = cachedAuthor
        )

        clearCompletedRunState()
        setProcessingUi(active = true)
        DiagLog.clear()
        logButton.text = getString(R.string.show_diagnostic_log)
        logButton.visibility = View.GONE
        saveLogButton.visibility = View.GONE
        logView.text = ""
        logView.visibility = View.GONE
        val videoHeader = when {
            cachedTitle == null -> ""
            cachedAuthor == null -> getString(R.string.video_title_only, cachedTitle)
            else -> getString(R.string.video_title_author, cachedTitle, cachedAuthor)
        }
        val operation = buildString {
            if (removeSegments) {
                append(
                    getString(
                        if (localSegments != null) {
                            R.string.local_segment_removal
                        } else {
                            R.string.sponsorblock_removal
                        }
                    )
                )
            }
            when (streamSelection) {
                StreamSelection.AUDIO_ONLY -> append(
                    getString(
                        if (removeSegments) {
                            R.string.audio_extraction_suffix
                        } else {
                            R.string.audio_extraction
                        }
                    )
                )

                StreamSelection.VIDEO_ONLY -> append(
                    getString(
                        if (removeSegments) {
                            R.string.video_extraction_suffix
                        } else {
                            R.string.video_extraction
                        }
                    )
                )

                StreamSelection.BOTH -> if (!removeSegments) {
                    append(getString(R.string.media_processing))
                }
            }
        }
        val modeLabel = getString(
            when {
                radioHwAccurate.isChecked -> R.string.hw_accurate_mode
                radioSwAccurate.isChecked -> R.string.sw_accurate_mode
                else -> R.string.fast_mode
            }
        )
        val processingHeader = cachedFileName
            ?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.file_name_with_icon, it) }
            ?: videoHeader

        val message = "$processingHeader\n\n" +
            getString(R.string.starting_job, "", operation, modeLabel)

        setStatus(message)

        val serviceIntent = Intent(this, ProcessingService::class.java).apply {
            action = ProcessingService.ACTION_PROCESS
            putExtra(ProcessingService.EXTRA_URI, uri.toString())
            putExtra(ProcessingService.EXTRA_VIDEO_ID, videoId)
            putExtra(ProcessingService.EXTRA_FRAME_ACCURATE, frameAccurate)
            putStringArrayListExtra(
                ProcessingService.EXTRA_CATEGORIES,
                ArrayList(selectedCategories())
            )
            putExtra(ProcessingService.EXTRA_HW_ACCURATE, radioHwAccurate.isChecked)
            putExtra(ProcessingService.EXTRA_REMOVE_SEGMENTS, removeSegments)
            putExtra(ProcessingService.EXTRA_STREAM_SELECTION, streamSelection.name)
            putExtra(
                ProcessingService.EXTRA_INPUT_SUMMARY,
                pendingVideoInfo?.summaryLine ?: "unknown"
            )
            putExtra(
                ProcessingService.EXTRA_SEGMENT_SOURCE_LABEL,
                segmentSourceLabel(videoId)
            )

            if (removeSegments) {
                // Serialize exactly the checked visible segments. The service
                // processes this payload directly, avoiding any mismatch
                // between UI indexes, category filtering, and source lists.
                val selectedVisibleSegments = currentSegments
                    .filterIndexed { index, _ -> index in selectedSegmentIndexes }

                if (selectedVisibleSegments.isNotEmpty()) {
                    putExtra(
                        ProcessingService.EXTRA_LOCAL_SEGMENTS_JSON,
                        localSegmentsJson(selectedVisibleSegments)
                    )
                } else if (localSegments != null) {
                    putExtra(
                        ProcessingService.EXTRA_LOCAL_SEGMENTS_JSON,
                        localSegmentsJson()
                    )
                }

                // Checkbox indexes are meaningful only for the SponsorBlock
                // branch. The local/manual JSON payload already contains exactly
                // the selected visible segments, so sending indexes too would
                // apply selection twice.
                if (selectedVisibleSegments.isEmpty() && localSegments == null) {
                    putExtra(
                        ProcessingService.EXTRA_SELECTED_SEGMENT_INDEXES,
                        selectedSegmentIndexes.toIntArray()
                    )
                }
            }
            putExtra(
                ProcessingService.EXTRA_OUTPUT_PREFERENCES_JSON,
                currentOutputPreferences().toJson()
            )
            if (outputFolderUri != null) putExtra(
                ProcessingService.EXTRA_OUTPUT_FOLDER_URI,
                outputFolderUri
            )
        }

        startForegroundService(serviceIntent)
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
            val controls = listOf(
                retryButton, browseFileButton, openPlayerButton,
                pickFolderButton, idInput, radioFast, radioSwAccurate, radioHwAccurate,
                radioKeepBoth, radioAudioOnly, radioVideoOnly,
                processWithoutSegmentsCheckBox, audioBitrateInput,
                videoSectionSpinner, audioSectionSpinner,
                containerSpinner, videoCodecSpinner, audioCodecSpinner,
                audioContainerSpinner,
                qualityModeSpinner, crfSpinner, cqSpinner,
                bitrateInput, bitratePresetSpinner, presetSpinner,
                frameRateSpinner, resolutionSpinner, audioSampleRateSpinner
            ) +
                    categoryCheckboxes.values.toList()
            for (v in controls) {
                v.isEnabled = !active
                v.alpha = if (active) 0.35f else 1f
            }
            cancelButton.visibility = if (active) View.VISIBLE else View.GONE
            cancelButton.isEnabled = active
            cancelButton.alpha = 1f
            cancelButton.text = getString(R.string.cancel_job)
            progressBar.visibility = if (active) View.VISIBLE else View.GONE
            loadSegmentsButton.isEnabled = !active
            loadSegmentsButton.alpha = if (active) 0.5f else 1f
            if (!active) {
                progressBar.progress = 0
                // Re-enable controls; retryButton state managed by updateCards()
                updateCards()
            }
        }
    }

    /**
     * After a SponsorBlock fetch, enable/disable checkboxes based on what actually
     * exists for this video and show segment counts on each label.
     */
    private fun updateCategoryAvailability(segments: List<SponsorSegmentInfo>?) {
        runOnUiThread {
            detectedCategories = segments
                ?.map { it.category }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
            renderDetectedCategories()
        }
    }

    private fun selectedCategories(): List<String> {
        return dynamicCategoryCheckboxes
            .filter { it.value.isChecked }
            .keys
            .toList()
            .sorted()
    }

    private fun saveId(videoId: String) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            putString(keyLastVideoId, videoId)
            putLong(keyLastVideoIdTs, System.currentTimeMillis())
        }
    }

    private fun saveFileUri(uri: Uri, displayName: String) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            putString(keyLastFileUri, uri.toString())
            putString(keyLastFileName, displayName)
        }
    }

    /** Restores last known file URI from prefs if pendingUri is still null. Shows it with a note. */
    private fun restoreLastFileUri() {
        if (pendingUri != null) return  // already have a file, don't overwrite
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val uriString = prefs.getString(keyLastFileUri, null) ?: return
        val fileName = prefs.getString(keyLastFileName, null) ?: "previous file"
        val uri = uriString.toUri()
        if (!canReadUri(uri)) {
            prefs.edit {
                remove(keyLastFileUri)
                remove(keyLastFileName)
            }
            return
        }
        pendingUri = uri
        fileCardState = CardState.OK
        runOnUiThread {
            fileCardLabel.text = getString(R.string.file_name_with_icon, fileName)
            browseFileButton.text = getString(R.string.change_file)
            updateCards()
        }
    }

    /** Returns true if the URI is an image (e.g. share-sheet preview JPG) — should be ignored as a file input. */
    private fun isImageUri(uri: Uri): Boolean {
        val mimeType = contentResolver.getType(uri) ?: return false
        return mimeType.startsWith("image/")
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
    private fun fetchSegmentsForId(
        videoId: String,
        force: Boolean = false,
        replaceLocalSourceOnSuccess: Boolean = false
    ) {
        if (ProcessingService.isProcessingActive) return
        if (videoId.length != 11) return
        if (!force && videoId == lastFetchedId) return
        lastFetchedId = videoId
        clearCompletedRunState()

        // A local TXT/JSON source remains active until SponsorBlock returns a
        // successful, non-empty segment list for the entered ID.
        cachedSegments = null
        cachedTitle = null
        cachedAuthor = null
        runOnUiThread {
            modeHintView.visibility = View.GONE
            highlightRadio(radioFast, false)
            highlightRadio(radioSwAccurate, false)
            idCardState = CardState.CHECKING
            updateCards()
        }
        runOnUiThread {
            retryButton.isEnabled = false
            retryButton.alpha = 0.5f
            retryButton.text = getString(R.string.checking_sponsorblock)
        }
        Thread {
            // Fetch title and segments in parallel
            var title: String? = null
            var author: String? = null
            try {
                val url =
                    java.net.URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
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
                SponsorBlockClient().fetchRich(
                    videoId,
                    listOf(
                        "sponsor", "selfpromo", "interaction", "intro", "outro",
                        "preview", "filler", "music_offtopic", "poi_highlight",
                        "exclusive_access", "chapter"
                    )
                )
            } catch (e: Exception) {
                Log.w(tag, "Segment pre-fetch failed: ${e.message}")
                null
            }
            // Only a direct user ID edit that produces a successful, non-empty
            // SponsorBlock response replaces a local TXT/JSON source. Automatic
            // fetches triggered by media or URL shares always preserve it.
            val sponsorBlockSourceReady =
                replaceLocalSourceOnSuccess &&
                        !segments.isNullOrEmpty()

            cachedSegments = segments
            cachedTitle = title
            cachedAuthor = author

            runOnUiThread {
                if (sponsorBlockSourceReady) {
                    clearLocalSegmentSource()
                }

                updateCategoryAvailability(segments)
                idCardState = when {
                    segments == null -> CardState.ERROR
                    segments.isNullOrEmpty() -> CardState.ERROR
                    else -> CardState.OK
                }
                applySegmentUiState(videoId, segments, title, author)
                updateCards()
                applyModeRecommendation()
            }
        }.start()
    }

    private fun applySegmentUiState(
        videoId: String, segments: List<SponsorSegmentInfo>?,
        title: String? = null, author: String? = null
    ) {
        val titleLine = when {
            title == null -> ""
            author == null -> getString(R.string.video_title_only, title)
            else -> getString(R.string.video_title_author, title, author)
        }
        when {
            segments == null -> {
                renderSegmentList(emptyList())
                retryButton.isEnabled = false
                retryButton.alpha = 0.4f
                retryButton.text = getString(R.string.no_segments_cannot_process)
                if (title != null) {
                    val currentStatus = statusView.text?.toString().orEmpty()
                    if (!currentStatus.contains(title)) {
                        statusView.text =
                            getString(R.string.status_with_title, titleLine, currentStatus)
                    }
                }
            }

            segments.isEmpty() -> {
                renderSegmentList(emptyList())
                retryButton.isEnabled = false
                retryButton.alpha = 0.4f
                retryButton.text = getString(R.string.no_segments_cannot_process)
                val currentStatus = statusView.text?.toString().orEmpty()
                val note = getString(R.string.no_sponsorblock_segments_for_id, videoId)
                val base =
                    if (title != null && !currentStatus.startsWith("\"")) titleLine + currentStatus else currentStatus
                if (!base.contains("No SponsorBlock segments")) {
                    statusView.text = getString(R.string.status_with_title, base, note)
                }
            }

            else -> {
                renderSegmentList(segments)
                renderDetectedCategories()
                retryButton.isEnabled = true
                retryButton.alpha = 1f
                retryButton.text = getString(R.string.process_with_id_button)
                val totalCut = segments.sumOf { it.end - it.start }
                val note = getString(
                    R.string.segments_to_cut,
                    segments.size,
                    formatSegmentTimestamp(totalCut)
                )
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
                val previousStatus = if (baseStatus.isNotBlank() && !baseStatus.startsWith("\"")) {
                    getString(R.string.status_with_title, baseStatus, "\n\n")
                } else {
                    ""
                }
                statusView.text = getString(
                    R.string.segment_status,
                    if (title != null) titleLine else "",
                    previousStatus,
                    note
                )
            }
        }
    }

    private fun renderSegmentList(
        segments: List<SponsorSegmentInfo>,
        preserveSelection: Boolean = false
    ) {
        val previousSelection =
            if (preserveSelection) selectedSegmentIndexes.toSet() else null

        segmentListContainer.removeAllViews()
        segmentCheckboxes.clear()
        currentSegments = segments
        invalidSegmentIndexes.clear()

        if (segments.isEmpty()) {
            selectedSegmentIndexes.clear()
            return
        }

        val title = TextView(this).apply {
            text = getString(R.string.segments_to_remove)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        segmentListContainer.addView(title)

        selectedSegmentIndexes.clear()
        if (preserveSelection && previousSelection != null) {
            selectedSegmentIndexes.addAll(
                previousSelection.filter { it in segments.indices }
            )
        } else {
            selectedSegmentIndexes.addAll(segments.indices)
        }

        val lastIndex = segments.lastIndex

        segments.forEachIndexed { index, segment ->
            segmentListContainer.addView(
                buildCompactSegmentRow(index, segment, index == lastIndex)
            )
        }
    }

    private fun isSegmentRowValid(segment: SponsorSegmentInfo): Boolean {
        if (segment.endIsOpen) {
            val duration = pendingVideoInfo?.durationSec ?: return false
            return segment.start < duration
        }

        if (segment.end <= segment.start) return false

        val duration = pendingVideoInfo?.durationSec
        return !(duration != null && segment.end > duration)
    }

    private fun circularControl(label: String, onClick: () -> Unit): TextView {
        val size = (26 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            text = label
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(2, 0, 2, 0)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF333333.toInt())
                setStroke(1, 0x66FFFFFF)
            }
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun buildCompactSegmentRow(
        index: Int,
        segment: SponsorSegmentInfo,
        isLastRow: Boolean
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 1, 0, 1)
        }

        row.addView(
            circularControl("−") {
                val updated = currentSegments.toMutableList()
                if (index in updated.indices) {
                    hasManualSegmentEdits = true
                    // Remove the deleted index and shift later selections so
                    // the user's enabled segments remain enabled.
                    val remappedSelection = selectedSegmentIndexes
                        .filter { it != index }
                        .map { if (it > index) it - 1 else it }
                        .toSet()

                    selectedSegmentIndexes.clear()
                    selectedSegmentIndexes.addAll(remappedSelection)

                    updated.removeAt(index)
                    renderSegmentList(updated, preserveSelection = true)
                    updateCards()
                }
            }
        )

        val checkbox = CheckBox(this).apply {
            isChecked = index in selectedSegmentIndexes
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedSegmentIndexes.add(index)
                } else {
                    selectedSegmentIndexes.remove(index)
                }

                suppressCategoryCallbacks = true
                syncCategoryCheckbox(segment.category)
                suppressCategoryCallbacks = false
            }
        }
        segmentCheckboxes[index] = checkbox
        row.addView(checkbox)

        if (segment.category == "manual") {
            val categoryEditor = EditText(this).apply {
                hint = getString(R.string.category_hint)
                setText(segment.category)
                textSize = 12f
                isSingleLine = true
                setPadding(4, 1, 4, 1)
                setBackgroundColor(0xFF222222.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    0.8f
                )
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val newCategory = text?.toString().orEmpty().trim()
                            .ifBlank { "manual" }

                        val updated = currentSegments.toMutableList()
                        if (index in updated.indices && newCategory != segment.category) {
                            updated[index] = updated[index].copy(category = newCategory)
                            currentSegments = updated
                            hasManualSegmentEdits = true

                            post {
                                renderSegmentList(currentSegments, preserveSelection = true)
                                updateCards()
                            }
                        }
                    }
                }
            }
            row.addView(categoryEditor)
        } else {
            row.addView(TextView(this).apply {
                text = getString(R.string.segment_category_display, segment.category)
                textSize = 12f
            })
        }

        val startView = timestampTextView(segment.start)
        startView.setOnClickListener {
            replaceWithTimestampEditor(row, startView, index, true)
        }
        row.addView(startView)

        row.addView(TextView(this).apply {
            text = "-"
            textSize = 12f
            setPadding(2, 0, 2, 0)
        })

        if (segment.endIsOpen) {
            row.addView(TextView(this).apply {
                text = getString(R.string.segment_end)
                textSize = 12f
            })
        } else {
            val endView = timestampTextView(segment.end)
            endView.setOnClickListener {
                replaceWithTimestampEditor(row, endView, index, false)
            }
            row.addView(endView)
        }

        if (isLastRow) {
            val endToken = CheckBox(this).apply {
                text = getString(R.string.segment_end)
                textSize = 10f
                isChecked = segment.endIsOpen
                isEnabled = pendingVideoInfo?.durationSec != null
                alpha = if (isEnabled) 1f else 0.4f
                setOnCheckedChangeListener { _, checked ->
                    val updated = currentSegments.toMutableList()
                    if (index in updated.indices) {
                        updated[index] = updated[index].copy(endIsOpen = checked)
                        hasManualSegmentEdits = true
                        renderSegmentList(updated, preserveSelection = true)
                    }
                }
            }
            row.addView(endToken)

            row.addView(
                circularControl("+") {
                    addManualSegment()
                }
            )
        }

        // Red means the start value conflicts with an already-entered end time.
        // An incomplete manual segment (end still 00:00:00.000) must not make
        // a correctly entered start time look invalid.
        val startConflictsWithEnd =
            !segment.endIsOpen &&
                    segment.end > 0.0 &&
                    segment.end <= segment.start

        val startInvalid = index in invalidSegmentIndexes || startConflictsWithEnd

        if (startInvalid) {
            startView.setTextColor(0xFFFF6666.toInt())
        } else {
            startView.setTextColor(0xFFFFFFFF.toInt())
        }

        return row
    }

    private fun timestampTextView(seconds: Double): TextView {
        return TextView(this).apply {
            text = formatSegmentTimestamp(seconds)
            textSize = 12f
            setPadding(4, 1, 4, 1)
            setBackgroundColor(0xFF222222.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun replaceWithTimestampEditor(
        row: LinearLayout,
        timestampView: TextView,
        index: Int,
        editingStart: Boolean
    ) {
        val segment = currentSegments.getOrNull(index) ?: return
        val initialSeconds = if (editingStart) segment.start else segment.end

        // Exactly nine digits are the editable state. ":" and "." are display
        // separators only and can never be deleted.
        val digits = StringBuilder(maskedDigitsFromSeconds(initialSeconds))
        var suppressMaskUpdate = false

        val editor = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            textSize = 12f
            isSingleLine = true
            setPadding(4, 1, 4, 1)
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setText(formatMaskedDigits(digits.toString()))
        }

        val maskWatcher = object : TextWatcher {
            private var oldText = ""
            private var changeStart = 0
            private var removedCount = 0
            private var addedCount = 0

            override fun beforeTextChanged(
                text: CharSequence?, start: Int, count: Int, after: Int
            ) {
                oldText = text?.toString().orEmpty()
                changeStart = start
                removedCount = count
                addedCount = after
            }

            override fun onTextChanged(
                text: CharSequence?, start: Int, before: Int, count: Int
            ) {
            }

            override fun afterTextChanged(editable: Editable?) {
                if (suppressMaskUpdate) return
                val editable = editable ?: return

                var targetDigitIndex: Int

                if (removedCount == 1 && addedCount == 0) {
                    // Backspace: replace one digit with zero. Never delete a
                    // separator or change the template length.
                    val deletedChar = oldText.getOrNull(changeStart)
                    val digitsBefore = oldText
                        .substring(0, changeStart)
                        .count { it.isDigit() }

                    val digitIndex = if (deletedChar?.isDigit() == true) {
                        digitsBefore
                    } else {
                        digitsBefore - 1
                    }

                    if (digitIndex in digits.indices) {
                        digits[digitIndex] = '0'
                    }

                    targetDigitIndex = digitIndex.coerceIn(0, digits.length - 1)
                } else {
                    // Typing overwrites the digit at the cursor, then advances.
                    // This makes 000515 become 00:05:15.000.
                    val digitsBefore = oldText
                        .substring(0, changeStart)
                        .count { it.isDigit() }

                    val insertedEnd = (changeStart + addedCount)
                        .coerceAtMost(editable.length)

                    val insertedDigits = editable
                        .toString()
                        .getOrNull(changeStart)
                        ?.let {
                            editable.toString().substring(changeStart, insertedEnd)
                        }
                        .orEmpty()
                        .filter { it.isDigit() }

                    var digitIndex = digitsBefore
                    for (digit in insertedDigits) {
                        if (digitIndex > digits.lastIndex) break
                        digits[digitIndex] = digit
                        digitIndex++
                    }

                    targetDigitIndex = digitIndex.coerceIn(0, digits.length)
                }

                val formatted = formatMaskedDigits(digits.toString())

                // Avoid re-entrant TextWatcher updates. Remove, replace, then
                // reattach before moving the cursor.
                suppressMaskUpdate = true
                editor.removeTextChangedListener(this)
                editor.setText(formatted)
                editor.addTextChangedListener(this)
                suppressMaskUpdate = false

                val cursorPosition = if (targetDigitIndex >= digits.length) {
                    formatted.length
                } else {
                    digitPosition(formatted, targetDigitIndex)
                }

                editor.setSelection(cursorPosition.coerceIn(0, formatted.length))
            }

            fun digitPosition(formatted: String, digitIndex: Int): Int {
                var seen = 0
                for (position in formatted.indices) {
                    if (formatted[position].isDigit()) {
                        if (seen == digitIndex) return position
                        seen++
                    }
                }
                return formatted.length
            }
        }

        editor.addTextChangedListener(maskWatcher)

        editor.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                editor.clearFocus()
                true
            } else {
                false
            }
        }

        editor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = maskedFieldValue(editor)
                val isValid = value != null

                if (index < currentSegments.size) {
                    if (isValid) {
                        invalidSegmentIndexes.remove(index)

                        val updated = currentSegments.toMutableList()
                        updated[index] = if (editingStart) {
                            updated[index].copy(start = value)
                        } else {
                            updated[index].copy(end = value, endIsOpen = false)
                        }
                        currentSegments = updated
                        hasManualSegmentEdits = true
                    } else {
                        invalidSegmentIndexes.add(index)
                    }
                }

                editor.post {
                    renderSegmentList(currentSegments, preserveSelection = true)
                    updateCards()
                }
            }
        }

        val editorIndex = row.indexOfChild(timestampView)
        row.removeView(timestampView)
        row.addView(editor, editorIndex)

        editor.requestFocus()
        editor.setSelection(0)

        val imm = getSystemService(INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun maskedDigitsFromSeconds(seconds: Double): String {
        val totalMillis = (seconds * 1000).toLong()
        val hh = totalMillis / 3600000
        val mm = (totalMillis % 3600000) / 60000
        val ss = (totalMillis % 60000) / 1000
        val mmm = totalMillis % 1000

        return String.format(Locale.ROOT, "%02d%02d%02d%03d", hh, mm, ss, mmm)
    }

    private fun formatMaskedDigits(digits: String): String {
        val d = digits.take(9)
        val parts = mutableListOf<String>()

        if (d.isNotEmpty()) parts += d.take(2)
        if (d.length > 2) parts += d.substring(2, 4)
        if (d.length > 4) parts += d.substring(4, 6)
        if (d.length > 6) parts += d.substring(6)

        val timePart = parts.take(3).joinToString(":")
        val millisPart = if (d.length > 6) parts.last() else ""

        return if (millisPart.isEmpty()) timePart else "$timePart.$millisPart"
    }

    private fun maskedFieldValue(field: EditText): Double? {
        val digits = field.text?.toString().orEmpty().filter { it.isDigit() }
        if (digits.length != 9) return null

        val hh = digits.substring(0, 2).toLong()
        val mm = digits.substring(2, 4).toLong()
        val ss = digits.substring(4, 6).toLong()
        val mmm = digits.substring(6, 9).toLong()

        if (mm > 59 || ss > 59 || mmm > 999) return null

        return hh * 3600.0 + mm * 60.0 + ss + mmm / 1000.0
    }

    private fun addManualSegment() {
        hasManualSegmentEdits = true
        val updated = currentSegments.toMutableList()
        updated.add(
            SponsorSegmentInfo(
                category = "manual",
                start = 0.0,
                end = 0.0
            )
        )

        // A newly added manual segment should always start selected, even when
        // every previously displayed segment was unchecked.
        selectedSegmentIndexes.add(updated.lastIndex)

        renderSegmentList(updated, preserveSelection = true)
        syncCategoryCheckbox("manual")
        updateCards()
    }

    private fun beginSaveSegments(format: String) {
        val segments = currentSegments
        if (segments.isEmpty()) {
            toast("No segments to save.")
            return
        }

        val baseName = pendingUri?.let { uri ->
            runCatching { FileResolver.getDisplayName(this, uri) }.getOrNull()
        }?.substringBeforeLast('.').takeUnless { it.isNullOrBlank() } ?: "output"

        val fileName = "$baseName-segments.$format"
        val mime = if (format == "json") "application/json" else "text/plain"
        val content = if (format == "json") {
            segmentsJsonExport(segments)
        } else {
            segmentsTxtExport(segments)
        }

        val folderUri = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(keyOutputFolderUri, null)
            ?.let { runCatching { it.toUri() }.getOrNull() }

        if (folderUri != null) {
            try {
                val documentUri = android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    folderUri,
                    mime,
                    fileName
                )

                if (documentUri != null) {
                    contentResolver.openOutputStream(documentUri)?.use { stream ->
                        stream.write(content.toByteArray())
                    }

                    setStatus("Segments saved:\n$documentUri")
                    return
                }
            } catch (e: Exception) {
                DiagLog.append("SegmentExport", "Output-folder save failed: ${e.message}")
            }
        }

        pendingSegmentSaveFormat = format
        val request = if (format == "json") {
            saveSegmentsJsonRequestCode
        } else {
            saveSegmentsTxtRequestCode
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }

        startActivityForResult(intent, request)
    }

    private fun formatSegmentExportTimestamp(seconds: Double): String {
        val totalMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
        val hours = totalMs / 3_600_000
        val minutes = (totalMs % 3_600_000) / 60_000
        val secs = (totalMs % 60_000) / 1000
        val millis = totalMs % 1000

        return if (hours > 0) {
            "%d:%02d:%02d.%03d".format(hours, minutes, secs, millis)
        } else {
            "%d:%02d.%03d".format(minutes, secs, millis)
        }
    }

    private fun segmentsTxtExport(segments: List<SponsorSegmentInfo>): String {
        return segments.joinToString("\n") { segment ->
            val end = if (segment.endIsOpen) {
                "end"
            } else {
                formatSegmentExportTimestamp(segment.end)
            }

            formatSegmentExportTimestamp(segment.start) + " - " + end + " " + segment.category
        } + "\n"
    }

    private fun segmentsJsonExport(segments: List<SponsorSegmentInfo>): String {
        val array = org.json.JSONArray()

        segments.forEach { segment ->
            val interval = org.json.JSONArray()
            interval.put(segment.start)
            interval.put(segment.end)

            array.put(
                org.json.JSONObject().apply {
                    put("category", segment.category)
                    put("segment", interval)
                }
            )
        }

        return array.toString(2)
    }

    private fun syncCategoryCheckbox(category: String) {
        val indexes = currentSegments.mapIndexedNotNull { index, segment ->
            if (segment.category == category) index else null
        }

        val checkbox = dynamicCategoryCheckboxes[category] ?: return
        checkbox.isChecked = indexes.isNotEmpty() &&
                indexes.all { it in selectedSegmentIndexes }
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
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

            val segmentationEnabled = processWithoutSegmentsCheckBox.isChecked

            if (!segmentationEnabled) {
                val greyShape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12f * resources.displayMetrics.density
                    setColor(0x22888888)
                    setStroke(
                        (1.5f * resources.displayMetrics.density).toInt(),
                        0x66888888.toInt()
                    )
                }
                idCardView.background = greyShape
                idCardIcon.alpha = 0.45f
                idInput.alpha = 0.45f
                loadSegmentsButton.alpha = 0.45f

                segmentCategoryContainer.visibility = View.GONE
                segmentListContainer.visibility = View.GONE

                if (localSegmentFileName != null) {
                    idCardNote.text = getString(
                        R.string.segment_file_loaded_ignored,
                        localSegmentFileName
                    )
                    idCardNote.visibility = View.VISIBLE
                } else {
                    idCardNote.visibility = View.GONE
                }
            } else {
                idCardIcon.alpha = 1f
                idInput.alpha = 1f
                loadSegmentsButton.alpha = 1f
                idCardNote.visibility = View.GONE

                segmentCategoryContainer.visibility = View.VISIBLE
                segmentListContainer.visibility = View.VISIBLE
            }

            (segmentListContainer.tag as? View)?.visibility =
                if (segmentationEnabled) View.VISIBLE else View.GONE

            val hasExportableSegments = segmentationEnabled && currentSegments.isNotEmpty()
            saveSegmentsTxtButton.isEnabled = hasExportableSegments
            saveSegmentsTxtButton.alpha = if (hasExportableSegments) 1f else 0.45f
            saveSegmentsJsonButton.isEnabled = hasExportableSegments
            saveSegmentsJsonButton.alpha = if (hasExportableSegments) 1f else 0.45f

            val localSourceReady = segmentationEnabled && localSegments != null
            val manualSourceReady = segmentationEnabled && currentSegments.isNotEmpty()
            val mediaReady = fileCardState == CardState.OK
            val hasInvalidSegments = invalidSegmentIndexes.isNotEmpty() ||
                    (segmentationEnabled && currentSegments.any { !isSegmentRowValid(it) })

            val canProcess = mediaReady &&
                    !(segmentationEnabled && hasInvalidSegments)

            retryButton.isEnabled = canProcess
            retryButton.alpha = if (canProcess) 1f else 0.5f
            retryButton.text = when {
                !segmentationEnabled -> "▶ Process without segmentation"
                localSourceReady -> "▶ Process local segments"
                manualSourceReady -> "▶ Process manual segments"
                idCardState == CardState.CHECKING -> "Checking SponsorBlock…"
                idCardState == CardState.ERROR -> "No segments — cannot process"
                !canProcess -> "Process with this ID"
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
                durationMs =
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                val mime =
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        ?: ""
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
                    highlightRadio(radioSwAccurate, false)
                    highlightRadio(radioHwAccurate, false)
                } else {
                    modeHintView.text = hint
                    modeHintView.visibility = View.VISIBLE
                    highlightRadio(radioFast, recommendFast)
                    highlightRadio(radioSwAccurate, !recommendFast)
                    highlightRadio(radioHwAccurate, false)
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
            withStyledAttributes(attrs = intArrayOf(android.R.attr.textColorPrimary)) {
                button.setTextColor(getColor(0, android.graphics.Color.WHITE))
            }
        }
    }

    private fun startDotAnim(baseText: String) {
        stopDotAnim()
        dotBaseText = baseText
        dotPhaseIdx = 0

        val header = when {
            !cachedFileName.isNullOrBlank() ->
                getString(R.string.file_name_with_icon, cachedFileName)

            cachedTitle != null && cachedAuthor != null ->
                getString(
                    R.string.video_title_author,
                    cachedTitle,
                    cachedAuthor
                )

            cachedTitle != null ->
                getString(R.string.video_title_only, cachedTitle)

            else -> ""
        }

        val tick = object : Runnable {
            override fun run() {
                if (dotRunnable !== this) return

                val status = getString(
                    R.string.animated_status,
                    header,
                    dotBaseText,
                    dotPhases[dotPhaseIdx]
                )

                statusView.text = status
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

    // Similar time-format helper exists in FfmpegEngine (formatTimestamp), but this one is
    // intentionally separate: segment preview in the selection UI needs h:mm:ss.ms precision,
    // while engine progress favors coarser, fast-to-scan timestamps for runtime updates.
    private fun formatSegmentTimestamp(seconds: Double): String {
        val totalMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
        val h = totalMs / 3_600_000
        val m = (totalMs % 3_600_000) / 60_000
        val s = (totalMs % 60_000) / 1_000
        val ms = totalMs % 1_000
        return "%d:%02d:%02d.%03d".format(h, m, s, ms)
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            stopDotAnim()
            statusView.text = text
        }
    }

    private fun saveDiagnosticLog() {
        val folderUri = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(keyOutputFolderUri, null)
        val outputFileName = getSharedPreferences(prefsName, MODE_PRIVATE)
            .getString(keyLastOutputFileName, null)

        if (folderUri.isNullOrBlank()) {
            setStatus("Set an output folder before saving the diagnostic log.")
            return
        }

        if (outputFileName.isNullOrBlank()) {
            setStatus("No completed output is available for a diagnostic-log file name.")
            return
        }

        val logFileName = "${outputFileName.substringBeforeLast('.', outputFileName)}.txt"
        val logText = DiagLog.get().ifBlank { "(log is empty)" }

        try {
            FileResolver.saveTextToOutputFolder(
                context = this,
                treeUri = folderUri.toUri(),
                fileName = logFileName,
                text = logText
            )
            setStatus("Diagnostic log saved:\n$logFileName")
        } catch (e: Exception) {
            setStatus("Failed to save diagnostic log:\n${e.message}")
        }
    }

    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private fun rejectIfBusy(): Boolean {
        if (!ProcessingService.isProcessingActive) return false
        // Keep existing job state untouched; reject new incoming share/direct intents.
        val msg = "please wait for current job to conclude and try again..."
        runOnUiThread {
            toast(msg)
            restoreActiveProcessingSnapshotIfNeeded()
            setProcessingUi(active = true)
        }
        return true
    }

    private fun canReadUri(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isUriAccessError(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("cannot open uri") ||
                t.contains("bad uri") ||
                t.contains("permission denied") ||
                t.contains("no such file")
    }

    private fun saveCompletedRunState(status: String, log: String) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            putString(keyLastCompletedStatus, status)
            putString(keyLastCompletedLog, log)
        }
        if (this::clearSummaryButton.isInitialized) {
            clearSummaryButton.isEnabled = true
            clearSummaryButton.alpha = 1f
            clearSummaryButton.visibility = View.VISIBLE
        }
    }

    private fun clearCompletedRunState() {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            remove(keyLastCompletedStatus)
            remove(keyLastCompletedLog)
        }
        if (this::clearSummaryButton.isInitialized) {
            clearSummaryButton.isEnabled = false
            clearSummaryButton.alpha = 0.5f
            clearSummaryButton.visibility = View.GONE
        }
    }

    private fun restoreCompletedRunStateIfNeeded() {
        if (ProcessingService.isProcessingActive) return

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val status = prefs.getString(keyLastCompletedStatus, null)
        val log = prefs.getString(keyLastCompletedLog, null)

        if (status.isNullOrBlank() && log.isNullOrBlank()) return

        if (!status.isNullOrBlank()) {
            setStatus(status)
        }

        if (!log.isNullOrBlank()) {
            logView.text = log
            logButton.visibility = View.VISIBLE
            logView.visibility = View.GONE
        }
        saveLogButton.visibility = View.VISIBLE

        // The Clear Summary control must remain available for exactly as long
        // as its restored summary remains available.
        if (this::clearSummaryButton.isInitialized) {
            clearSummaryButton.isEnabled = true
            clearSummaryButton.alpha = 1f
            clearSummaryButton.visibility = View.VISIBLE
        }
    }

    private fun saveActiveProcessingSnapshot(
        videoId: String,
        uri: Uri,
        fileName: String,
        title: String?,
        author: String?
    ) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            putString(keyActiveProcessingId, videoId)
            putString(keyActiveProcessingUri, uri.toString())
            putString(keyActiveProcessingFileName, fileName)
            putString(keyActiveProcessingTitle, title)
            putString(keyActiveProcessingAuthor, author)
        }
    }

    private fun clearActiveProcessingSnapshot() {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            remove(keyActiveProcessingId)
            remove(keyActiveProcessingUri)
            remove(keyActiveProcessingFileName)
            remove(keyActiveProcessingTitle)
            remove(keyActiveProcessingAuthor)
        }
    }

    private fun restoreActiveProcessingSnapshotIfNeeded() {
        if (!ProcessingService.isProcessingActive) return

        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val activeId = prefs.getString(keyActiveProcessingId, "").orEmpty()
        if (activeId.isBlank()) return

        val activeUri = prefs.getString(keyActiveProcessingUri, null)?.let {
            runCatching { it.toUri() }.getOrNull()
        }
        val activeFileName = prefs.getString(keyActiveProcessingFileName, null)
        val activeTitle = prefs.getString(keyActiveProcessingTitle, null)
        val activeAuthor = prefs.getString(keyActiveProcessingAuthor, null)

        pendingUri = activeUri
        cachedTitle = activeTitle
        cachedAuthor = activeAuthor
        cachedFileName = activeFileName

        // Restore media probing and output-field state for the active file.
        if (activeUri != null && pendingVideoInfo == null) {
            inspectSelectedUri(activeUri)
        }

        runOnUiThread {
            setIdInputProgrammatically(activeId)
            idCardState = CardState.OK

            if (!activeFileName.isNullOrBlank()) {
                fileCardLabel.text = getString(R.string.file_name_with_icon, activeFileName)
                browseFileButton.text = getString(R.string.change_file)
                fileCardState = CardState.OK
            }

            val header = buildString {
//                if (!activeTitle.isNullOrBlank()) {
////                    append("\"$activeTitle\"")
//                    append("$activeTitle")
//                    if (!activeAuthor.isNullOrBlank()) append("\nby $activeAuthor")
//                    append("\n\n")
//                }
                if (!activeFileName.isNullOrBlank()) {
                    append(getString(R.string.file_name_with_icon, activeFileName))
                    append("\n\n")
                }
                append(getString(R.string.background_processing))
            }
            setStatus(header)
            updateCards()
        }
    }
}
