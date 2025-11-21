package com.example.zipcrackerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var selectZipBtn: Button
    private lateinit var passwordListBtn: Button
    private lateinit var startCrackBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var threadCountInput: EditText
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultText: TextView
    private lateinit var attemptsText: TextView

    private lateinit var autoBenchmarkToggle: Switch

    private var selectedZipUri: Uri? = null
    private var passwordFileLocal: File? = null
    private var passwordCount: Int = 0
    private var crackingJob: Job? = null
    private val shouldStop = AtomicBoolean(false)

    companion object {
        const val PICK_ZIP_FILE = 1
        const val PICK_PASSWORD_FILE = 2
        const val PERMISSION_REQUEST = 100
        // default benchmark duration (ms)
        const val DEFAULT_BENCHMARK_MS = 1500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadDefaultPasswordsIfMissing()
        checkPermissions()
        setupListeners()
    }

    private fun initViews() {
        selectZipBtn = findViewById(R.id.selectZipBtn)
        passwordListBtn = findViewById(R.id.passwordListBtn)
        startCrackBtn = findViewById(R.id.startCrackBtn)
        stopBtn = findViewById(R.id.stopBtn)
        threadCountInput = findViewById(R.id.threadCountInput)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        resultText = findViewById(R.id.resultText)
        attemptsText = findViewById(R.id.attemptsText)
        // remember to add this Switch to your layout with id autoBenchmarkToggle
        autoBenchmarkToggle = findViewById(R.id.autoBenchmarkToggle)

        stopBtn.isEnabled = false

        // Smarter default: number of available processors (at least 2)
        val defaultThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        threadCountInput.setText(defaultThreads.toString())

        // default toggle state: enabled
        autoBenchmarkToggle.isChecked = true
    }

    private fun checkPermissions() {
        // For Android 11+, open document does not necessarily require runtime permission,
        // but request read/write for other file operations when needed.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                PERMISSION_REQUEST
            )
        }
    }

    private fun setupListeners() {
        selectZipBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed"))
            }
            startActivityForResult(intent, PICK_ZIP_FILE)
        }

        passwordListBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }
            startActivityForResult(intent, PICK_PASSWORD_FILE)
        }

        startCrackBtn.setOnClickListener {
            startCracking()
        }

        stopBtn.setOnClickListener {
            stopCracking()
        }
    }

    private fun loadDefaultPasswordsIfMissing() {
        val passwordFile = File(filesDir, "passwords.txt")
        if (!passwordFile.exists()) {
            val defaultPasswords = """
                123456
                password
                12345678
                qwerty
                abc123
                monkey
                1234567
                letmein
                trustno1
                dragon
                baseball
                iloveyou
                master
                sunshine
                ashley
                bailey
                shadow
                123123
                654321
                superman
                qazwsx
                michael
                football
            """.trimIndent()
            passwordFile.writeText(defaultPasswords)
        }
        passwordFileLocal = passwordFile
        passwordCount = passwordFile.useLines { it.count() }
        updateStatus("Loaded default password file with $passwordCount entries")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK && data != null) {
            when (requestCode) {
                PICK_ZIP_FILE -> {
                    selectedZipUri = data.data
                    try {
                        selectedZipUri?.let { uri ->
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } catch (_: Exception) {}
                    updateStatus("ZIP file selected: ${selectedZipUri?.lastPathSegment ?: selectedZipUri?.path}")
                }
                PICK_PASSWORD_FILE -> {
                    data.data?.let { uri ->
                        val pair = copyPasswordFileAndCountLines(uri)
                        if (pair != null) {
                            passwordFileLocal = pair.first
                            passwordCount = pair.second
                            updateStatus("Password file loaded: ${passwordFileLocal?.name} ($passwordCount lines)")
                        }
                    }
                }
            }
        }
    }

    private fun copyPasswordFileAndCountLines(uri: Uri): Pair<File, Int>? {
        return try {
            val dest = File(filesDir, "passwords.txt")
            var lineCount = 0
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                dest.bufferedWriter().use { writer ->
                    reader.forEachLine { rawLine ->
                        val line = rawLine.trim()
                        if (line.isNotEmpty()) {
                            writer.write(line)
                            writer.write("\n")
                            lineCount++
                        }
                    }
                    writer.flush()
                }
            }
            Pair(dest, lineCount)
        } catch (e: Exception) {
            updateStatus("Error loading passwords: ${e.message}")
            null
        }
    }

    private fun startCracking() {
        if (selectedZipUri == null) {
            updateStatus("Please select a ZIP file first")
            return
        }

        val pwFile = passwordFileLocal
        if (pwFile == null || !pwFile.exists()) {
            updateStatus("Password file not available. Please load a password file first.")
            return
        }

        // Parse initial thread count (used as fallback)
        val parsedThreadCount = threadCountInput.text.toString().toIntOrNull()
            ?: Runtime.getRuntime().availableProcessors()

        if (parsedThreadCount < 1 || parsedThreadCount > 128) {
            updateStatus("Thread count must be between 1 and 128")
            return
        }

        shouldStop.set(false)
        startCrackBtn.isEnabled = false
        stopBtn.isEnabled = true
        selectZipBtn.isEnabled = false
        passwordListBtn.isEnabled = false
        progressBar.isIndeterminate = false
        progressBar.max = passwordCount
        progressBar.progress = 0
        resultText.text = ""
        attemptsText.text = ""

        // Launch cracking job on Main so UI updates safe
        crackingJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                // Read toggle state and decide whether to run auto-benchmark
                val autoBenchmark = autoBenchmarkToggle.isChecked
                var finalThreadCount = parsedThreadCount // fallback to parsed value

                if (autoBenchmark) {
                    // Make a temp copy so benchmark doesn't advance the main password file
                    val benchPwFile = copyFileToCache(pwFile, prefix = "pw_bench")
                    try {
                        val cores = Runtime.getRuntime().availableProcessors()
                        val candidates = (1..max(1, cores * 2)).toList().filter { it <= 64 }
                        val suggested = findBestThreadCount(selectedZipUri!!, benchPwFile, passwordCount, candidates, trialMs = DEFAULT_BENCHMARK_MS)
                        // Use suggested directly (non-destructive)
                        threadCountInput.setText(suggested.toString())
                        finalThreadCount = suggested
                    } finally {
                        // cleanup bench copy
                        benchPwFile.delete()
                    }
                }

                // Reset progress UI before real run (benchmark may have updated it)
                progressBar.progress = 0
                attemptsText.text = ""
                updateStatus("Starting real run with $finalThreadCount threads...")

                // Now run the real cracking with the chosen thread count (finalThreadCount)
                val found = withContext(Dispatchers.Default) {
                    crackZipWithPasswordFile(selectedZipUri!!, pwFile, passwordCount, finalThreadCount)
                }

                if (found != null) {
                    if (found.isEmpty()) {
                        resultText.text = getString(R.string.password_found, "(empty)")
                        updateStatus("Zip not encrypted or empty password.")
                        saveFoundPassword(selectedZipUri!!.lastPathSegment ?: "unknown", "(empty)")
                    } else {
                        resultText.text = getString(R.string.password_found, found)
                        updateStatus("Success! Password: $found")
                        saveFoundPassword(selectedZipUri!!.lastPathSegment ?: "unknown", found)
                    }
                } else {
                    resultText.text = getString(R.string.password_not_found_in_list)
                    updateStatus("Password not found")
                }
            } catch (e: CancellationException) {
                updateStatus("Cracking cancelled")
            } catch (e: Exception) {
                updateStatus("Error: ${e.message}")
            } finally {
                startCrackBtn.isEnabled = true
                stopBtn.isEnabled = false
                selectZipBtn.isEnabled = true
                passwordListBtn.isEnabled = true
            }
        }
    }

    private fun stopCracking() {
        shouldStop.set(true)
        crackingJob?.cancel()
        updateStatus("Cracking stopped")
        startCrackBtn.isEnabled = true
        stopBtn.isEnabled = false
        selectZipBtn.isEnabled = true
        passwordListBtn.isEnabled = true
    }

    /**
     * Utility: copy a file to cache and return the new file reference.
     * Used to create a non-destructive password file copy for benchmarking.
     */
    private fun copyFileToCache(src: File, prefix: String = "pw_bench"): File {
        val dest = File.createTempFile(prefix, ".txt", cacheDir)
        src.inputStream().use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return dest
    }

    /**
     * Improved streaming crack routine (drop-in replacement).
     * - Batch producer (Channel<List<String>>)
     * - Workers on Dispatchers.Default (CPU-bound)
     * - Per-worker buffer reuse
     * - Optional maxDurationMs for short benchmarks
     */
    private suspend fun crackZipWithPasswordFile(
        zipUri: Uri,
        passwordFile: File,
        totalPasswords: Int,
        threadCount: Int,
        maxDurationMs: Long = 0L
    ): String? = withContext(Dispatchers.IO) {
        // copy zip to temp file in cache (so ZipFile can open it many times)
        val tempZipFile = File(cacheDir, "temp_${System.currentTimeMillis()}.zip")
        contentResolver.openInputStream(zipUri)?.use { input ->
            FileOutputStream(tempZipFile).use { output ->
                input.copyTo(output)
            }
        }

        val batchSize = 64
        val channelCapacity = 200
        val channel = Channel<List<String>>(capacity = channelCapacity)

        val foundPassword = AtomicBoolean(false)
        val resultPassword = AtomicReference<String?>(null)
        val testedCount = AtomicInteger(0)
        val uiUpdateBatch = 1000
        val startTime = System.currentTimeMillis()

        // Producer: read password file in batches and send to channel
        val producer = CoroutineScope(Dispatchers.IO).launch {
            try {
                val batch = ArrayList<String>(batchSize)
                passwordFile.bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        if (shouldStop.get() || foundPassword.get()) break
                        val pw = raw.trim()
                        if (pw.isNotEmpty()) {
                            batch.add(pw)
                            if (batch.size >= batchSize) {
                                channel.send(ArrayList(batch))
                                batch.clear()
                            }
                        }
                        if (maxDurationMs > 0L) {
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed >= maxDurationMs) break
                        }
                    }
                }
                if (batch.isNotEmpty() && !foundPassword.get() && !shouldStop.get()) {
                    channel.send(ArrayList(batch))
                    batch.clear()
                }
            } catch (e: CancellationException) {
                // cancelled
            } catch (e: Exception) {
                // ignore, optionally log
            } finally {
                channel.close()
            }
        }

        // Worker coroutines
        val workers = mutableListOf<Deferred<Unit>>()
        repeat(threadCount) { workerIndex ->
            val w = async(Dispatchers.Default) {
                val zipFile = ZipFile(tempZipFile)
                val buffer = ByteArray(32)
                try {
                    for (batch in channel) {
                        if (shouldStop.get() || foundPassword.get()) break
                        for (password in batch) {
                            if (shouldStop.get() || foundPassword.get()) break

                            if (maxDurationMs > 0L) {
                                val elapsed = System.currentTimeMillis() - startTime
                                if (elapsed >= maxDurationMs) {
                                    shouldStop.set(true)
                                    break
                                }
                            }

                            try {
                                if (zipFile.isEncrypted) {
                                    zipFile.setPassword(password.toCharArray())
                                    val headers: List<FileHeader> = zipFile.fileHeaders
                                    if (headers.isNotEmpty()) {
                                        val header = headers[0]
                                        try {
                                            zipFile.getInputStream(header).use { input ->
                                                val read = input.read(buffer, 0, buffer.size)
                                                if (read >= 0) {
                                                    foundPassword.set(true)
                                                    resultPassword.set(password)
                                                    break
                                                }
                                            }
                                        } catch (ex: Exception) {
                                            // wrong password
                                        }
                                    } else {
                                        foundPassword.set(true)
                                        resultPassword.set("")
                                        break
                                    }
                                } else {
                                    foundPassword.set(true)
                                    resultPassword.set("")
                                    break
                                }
                            } catch (e: Exception) {
                                // ignore and continue
                            } finally {
                                val count = testedCount.incrementAndGet()
                                if (count % uiUpdateBatch == 0 || count == totalPasswords || foundPassword.get()) {
                                    val now = System.currentTimeMillis()
                                    val elapsedSec = max((now - startTime) / 1000.0, 1.0)
                                    val attemptsPerSec = count / elapsedSec
                                    withContext(Dispatchers.Main) {
                                        progressBar.progress = count.coerceAtMost(progressBar.max)
                                        updateStatus("Tested $count / $totalPasswords")
                                        attemptsText.text = String.format("%.1f attempts/sec", attemptsPerSec)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    // cleanup if needed
                }
            }
            workers.add(w)
        }

        // Wait loop
        try {
            while (!foundPassword.get() && !shouldStop.get() && workers.any { !it.isCompleted }) {
                if (maxDurationMs > 0L) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= maxDurationMs) {
                        shouldStop.set(true)
                        break
                    }
                }
                delay(100)
            }
            workers.forEach { it.cancel() }
        } finally {
            producer.cancel()
            channel.close()
            withContext(Dispatchers.Main) {
                val finalCount = testedCount.get()
                val now = System.currentTimeMillis()
                val elapsedSec = max((now - startTime) / 1000.0, 1.0)
                val attemptsPerSec = finalCount / elapsedSec
                progressBar.progress = finalCount.coerceAtMost(progressBar.max)
                updateStatus(
                    when {
                        foundPassword.get() -> "Password found"
                        shouldStop.get() -> if (maxDurationMs > 0L) "Benchmark stopped" else "Stopped"
                        else -> "Finished"
                    }
                )
                attemptsText.text = String.format("%.1f attempts/sec", attemptsPerSec)
            }
            tempZipFile.delete()
        }

        resultPassword.get()
    }

    /**
     * Micro-benchmark helper: try multiple thread counts for short trials and return the best.
     * - threadCandidates: list to test
     * - trialMs: duration per candidate
     */
    private suspend fun findBestThreadCount(
        zipUri: Uri,
        passwordFile: File,
        passwordCount: Int,
        threadCandidates: List<Int>,
        trialMs: Long = DEFAULT_BENCHMARK_MS
    ): Int = withContext(Dispatchers.Main) {
        var bestThreads = threadCandidates.first()
        var bestRate = 0.0
        updateStatus("Benchmarking thread counts...")

        for (tc in threadCandidates) {
            // Reset progress bar for an isolated measurement
            progressBar.progress = 0
            attemptsText.text = ""
            updateStatus("Testing $tc threads...")

            // Run a short trial (maxDurationMs stops the run automatically)
            val beforeTime = System.currentTimeMillis()
            try {
                withContext(Dispatchers.Default) {
                    // This will stop after trialMs due to maxDurationMs param
                    crackZipWithPasswordFile(zipUri, passwordFile, passwordCount, tc, maxDurationMs = trialMs)
                }
            } catch (_: Exception) {
                // swallow; we expect the function to return null for benchmark runs
            }
            val afterTime = System.currentTimeMillis()
            val elapsedSec = max((afterTime - beforeTime) / 1000.0, 0.001)
            val attempted = progressBar.progress
            val rate = attempted / elapsedSec

            updateStatus("Threads $tc: ${"%.1f".format(rate)} attempts/sec")
            if (rate > bestRate) {
                bestRate = rate
                bestThreads = tc
            }

            // small pause to let the device settle
            delay(250)
        }

        updateStatus("Benchmark complete. Best: $bestThreads threads (~${"%.1f".format(bestRate)} attempts/sec)")
        bestThreads
    }

    private fun saveFoundPassword(zipName: String, password: String) {
        try {
            val foundFile = File(filesDir, "found_passwords.txt")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val entry = "$timestamp | $zipName | $password\n"
            foundFile.appendText(entry)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }
}
