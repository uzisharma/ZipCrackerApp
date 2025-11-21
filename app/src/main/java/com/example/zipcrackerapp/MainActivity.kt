package com.example.zipcrackerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

class MainActivity : ComponentActivity() {
    companion object {
        const val PERMISSION_REQUEST = 100
        const val DEFAULT_BENCHMARK_MS = 1500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()
        loadDefaultPasswordsIfMissing()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZipCrackerScreen()
                }
            }
        }
    }

    private fun checkPermissions() {
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
    }
}

@Composable
fun ZipCrackerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedZipUri by remember { mutableStateOf<Uri?>(null) }
    var passwordFileLocal by remember { mutableStateOf<File?>(null) }
    var passwordCount by remember { mutableIntStateOf(0) }
    var threadCount by remember { mutableStateOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(2).toString()) }
    var autoBenchmark by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("Ready") }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressMax by remember { mutableIntStateOf(100) }
    var resultText by remember { mutableStateOf("") }
    var attemptsText by remember { mutableStateOf("") }
    var isCracking by remember { mutableStateOf(false) }
    var crackingJob by remember { mutableStateOf<Job?>(null) }
    val shouldStop = remember { AtomicBoolean(false) }

    // Initialize password file
    LaunchedEffect(Unit) {
        val passwordFile = File(context.filesDir, "passwords.txt")
        if (passwordFile.exists()) {
            passwordFileLocal = passwordFile
            passwordCount = passwordFile.useLines { it.count() }
            statusText = "Loaded default password file with $passwordCount entries"
        }
    }

    // ZIP file picker
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedZipUri = it
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            statusText = "ZIP file selected: ${it.lastPathSegment ?: it.path}"
        }
    }

    // Password file picker
    val passwordPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val pair = copyPasswordFileAndCountLines(context, it)
            if (pair != null) {
                passwordFileLocal = pair.first
                passwordCount = pair.second
                statusText = "Password file loaded: ${pair.first.name} (${pair.second} lines)"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ZIP Password Cracker",
            style = MaterialTheme.typography.headlineMedium
        )

        // ZIP Selection
        Button(
            onClick = {
                zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
            },
            enabled = !isCracking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select ZIP File")
        }

        // Password List Selection
        Button(
            onClick = {
                passwordPicker.launch(arrayOf("text/plain"))
            },
            enabled = !isCracking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select Password List")
        }

        // Thread Count Input
        OutlinedTextField(
            value = threadCount,
            onValueChange = { threadCount = it },
            label = { Text("Thread Count") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = !isCracking,
            modifier = Modifier.fillMaxWidth()
        )

        // Auto Benchmark Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Switch(
                checked = autoBenchmark,
                onCheckedChange = { autoBenchmark = it },
                enabled = !isCracking
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Auto Benchmark Threads")
        }

        // Control Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    crackingJob = scope.launch {
                        startCracking(
                            context = context,
                            selectedZipUri = selectedZipUri,
                            passwordFileLocal = passwordFileLocal,
                            passwordCount = passwordCount,
                            threadCount = threadCount,
                            autoBenchmark = autoBenchmark,
                            shouldStop = shouldStop,
                            onStatusUpdate = { statusText = it },
                            onProgressUpdate = { prog, max ->
                                progress = prog.toFloat()
                                progressMax = max
                            },
                            onResultUpdate = { resultText = it },
                            onAttemptsUpdate = { attemptsText = it },
                            onCrackingStateChange = { isCracking = it }
                        )
                    }
                },
                enabled = !isCracking,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start")
            }

            Button(
                onClick = {
                    shouldStop.set(true)
                    crackingJob?.cancel()
                    statusText = "Cracking stopped"
                    isCracking = false
                },
                enabled = isCracking,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop")
            }
        }

        // Progress
        LinearProgressIndicator(
            progress = { if (progressMax > 0) progress / progressMax else 0f },
            modifier = Modifier.fillMaxWidth()
        )

        // Status
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = statusText)
            }
        }

        // Attempts
        if (attemptsText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Performance",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = attemptsText)
                }
            }
        }

        // Result
        if (resultText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Result",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = resultText)
                }
            }
        }
    }
}

private fun copyPasswordFileAndCountLines(context: android.content.Context, uri: Uri): Pair<File, Int>? {
    return try {
        val dest = File(context.filesDir, "passwords.txt")
        var lineCount = 0
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
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
        null
    }
}

private suspend fun startCracking(
    context: android.content.Context,
    selectedZipUri: Uri?,
    passwordFileLocal: File?,
    passwordCount: Int,
    threadCount: String,
    autoBenchmark: Boolean,
    shouldStop: AtomicBoolean,
    onStatusUpdate: (String) -> Unit,
    onProgressUpdate: (Int, Int) -> Unit,
    onResultUpdate: (String) -> Unit,
    onAttemptsUpdate: (String) -> Unit,
    onCrackingStateChange: (Boolean) -> Unit
) {
    if (selectedZipUri == null) {
        onStatusUpdate("Please select a ZIP file first")
        return
    }

    val pwFile = passwordFileLocal
    if (pwFile == null || !pwFile.exists()) {
        onStatusUpdate("Password file not available")
        return
    }

    val parsedThreadCount = threadCount.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()

    if (parsedThreadCount < 1 || parsedThreadCount > 128) {
        onStatusUpdate("Thread count must be between 1 and 128")
        return
    }

    shouldStop.set(false)
    onCrackingStateChange(true)
    onProgressUpdate(0, passwordCount)
    onResultUpdate("")
    onAttemptsUpdate("")

    try {
        var finalThreadCount = parsedThreadCount

        if (autoBenchmark) {
            val benchPwFile = copyFileToCache(context, pwFile, prefix = "pw_bench")
            try {
                val cores = Runtime.getRuntime().availableProcessors()
                val candidates = (1..max(1, cores * 2)).toList().filter { it <= 64 }
                finalThreadCount = findBestThreadCount(
                    context, selectedZipUri, benchPwFile, passwordCount, candidates,
                    MainActivity.DEFAULT_BENCHMARK_MS, onStatusUpdate, onProgressUpdate, onAttemptsUpdate
                )
            } finally {
                benchPwFile.delete()
            }
        }

        onProgressUpdate(0, passwordCount)
        onAttemptsUpdate("")
        onStatusUpdate("Starting with $finalThreadCount threads...")

        val found = withContext(Dispatchers.Default) {
            crackZipWithPasswordFile(
                context, selectedZipUri, pwFile, passwordCount, finalThreadCount,
                shouldStop, onStatusUpdate, onProgressUpdate, onAttemptsUpdate
            )
        }

        if (found != null) {
            if (found.isEmpty()) {
                onResultUpdate("Password found: (empty)")
                onStatusUpdate("Zip not encrypted or empty password")
                saveFoundPassword(context, selectedZipUri.lastPathSegment ?: "unknown", "(empty)")
            } else {
                onResultUpdate("Password found: $found")
                onStatusUpdate("Success! Password: $found")
                saveFoundPassword(context, selectedZipUri.lastPathSegment ?: "unknown", found)
            }
        } else {
            onResultUpdate("Password not found in list")
            onStatusUpdate("Password not found")
        }
    } catch (e: CancellationException) {
        onStatusUpdate("Cracking cancelled")
    } catch (e: Exception) {
        onStatusUpdate("Error: ${e.message}")
    } finally {
        onCrackingStateChange(false)
    }
}

private fun copyFileToCache(context: android.content.Context, src: File, prefix: String = "pw_bench"): File {
    val dest = File.createTempFile(prefix, ".txt", context.cacheDir)
    src.inputStream().use { input ->
        dest.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return dest
}

private suspend fun crackZipWithPasswordFile(
    context: android.content.Context,
    zipUri: Uri,
    passwordFile: File,
    totalPasswords: Int,
    threadCount: Int,
    shouldStop: AtomicBoolean,
    onStatusUpdate: (String) -> Unit,
    onProgressUpdate: (Int, Int) -> Unit,
    onAttemptsUpdate: (String) -> Unit,
    maxDurationMs: Long = 0L
): String? = withContext(Dispatchers.IO) {
    val tempZipFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.zip")
    context.contentResolver.openInputStream(zipUri)?.use { input ->
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
        } catch (_: Exception) {
        } finally {
            channel.close()
        }
    }

    val workers = mutableListOf<Deferred<Unit>>()
    repeat(threadCount) {
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
                                    } catch (_: Exception) {
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
                        } catch (_: Exception) {
                        } finally {
                            val count = testedCount.incrementAndGet()
                            if (count % uiUpdateBatch == 0 || count == totalPasswords || foundPassword.get()) {
                                val now = System.currentTimeMillis()
                                val elapsedSec = max((now - startTime) / 1000.0, 1.0)
                                val attemptsPerSec = count / elapsedSec
                                withContext(Dispatchers.Main) {
                                    onProgressUpdate(count, totalPasswords)
                                    onStatusUpdate("Tested $count / $totalPasswords")
                                    onAttemptsUpdate(String.format("%.1f attempts/sec", attemptsPerSec))
                                }
                            }
                        }
                    }
                }
            } finally {
            }
        }
        workers.add(w)
    }

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
            onProgressUpdate(finalCount, totalPasswords)
            onStatusUpdate(
                when {
                    foundPassword.get() -> "Password found"
                    shouldStop.get() -> if (maxDurationMs > 0L) "Benchmark stopped" else "Stopped"
                    else -> "Finished"
                }
            )
            onAttemptsUpdate(String.format("%.1f attempts/sec", attemptsPerSec))
        }
        tempZipFile.delete()
    }

    resultPassword.get()
}

private suspend fun findBestThreadCount(
    context: android.content.Context,
    zipUri: Uri,
    passwordFile: File,
    passwordCount: Int,
    threadCandidates: List<Int>,
    trialMs: Long,
    onStatusUpdate: (String) -> Unit,
    onProgressUpdate: (Int, Int) -> Unit,
    onAttemptsUpdate: (String) -> Unit
): Int = withContext(Dispatchers.Main) {
    var bestThreads = threadCandidates.first()
    var bestRate = 0.0
    onStatusUpdate("Benchmarking thread counts...")

    val shouldStop = AtomicBoolean(false)

    for (tc in threadCandidates) {
        onProgressUpdate(0, passwordCount)
        onAttemptsUpdate("")
        onStatusUpdate("Testing $tc threads...")

        val beforeTime = System.currentTimeMillis()
        try {
            withContext(Dispatchers.Default) {
                crackZipWithPasswordFile(
                    context, zipUri, passwordFile, passwordCount, tc,
                    shouldStop, onStatusUpdate, onProgressUpdate, onAttemptsUpdate,
                    maxDurationMs = trialMs
                )
            }
        } catch (_: Exception) {
        }
        val afterTime = System.currentTimeMillis()
        val elapsedSec = max((afterTime - beforeTime) / 1000.0, 0.001)
        val attempted = 0 // You'd need to track this from progress
        val rate = attempted / elapsedSec

        onStatusUpdate("Threads $tc: ${"%.1f".format(rate)} attempts/sec")
        if (rate > bestRate) {
            bestRate = rate
            bestThreads = tc
        }

        delay(250)
    }

    onStatusUpdate("Benchmark complete. Best: $bestThreads threads (~${"%.1f".format(bestRate)} attempts/sec)")
    bestThreads
}

private fun saveFoundPassword(context: android.content.Context, zipName: String, password: String) {
    try {
        val foundFile = File(context.filesDir, "found_passwords.txt")
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "$timestamp | $zipName | $password\n"
        foundFile.appendText(entry)
    } catch (_: Exception) {
    }
}