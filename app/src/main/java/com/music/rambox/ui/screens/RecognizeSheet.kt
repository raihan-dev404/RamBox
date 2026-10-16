package com.music.rambox.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.music.rambox.data.recognition.AcrCloudClient
import com.music.rambox.data.recognition.AcrCredentials
import com.music.rambox.data.recognition.MicRecorder
import com.music.rambox.data.recognition.RecognitionPrefs
import com.music.rambox.data.recognition.SpeechLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Hum or sing" — find a song from a tune or from a few sung words.
 *
 * Sing needs no setup (the phone's speech recogniser hears the words). Hum
 * records the mic and asks ACRCloud, so it asks once for the user's own
 * ACRCloud project keys. On a hit, [onSearch] is handed a query such as
 * "Artist Title" and the sheet closes, leaving the search results behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognizeSheet(onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var singMode by rememberSaveable { mutableStateOf(true) }
    var creds by remember { mutableStateOf(RecognitionPrefs.load(context)) }
    var editingKeys by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        job?.cancel()
        job = scope.launch {
            busy = true
            stopRequested = false
            level = 0f
            try {
                if (singMode) {
                    status = "Sing a line of the song…"
                    val text = SpeechLyrics.listen(context) { status = "“$it”" }
                    if (text.isBlank()) {
                        status = "Couldn't catch any words. Try again, a little louder."
                    } else {
                        onSearch(text)
                        onDismiss()
                    }
                } else {
                    status = "Hum the tune. Tap again when you're done."
                    recording = true
                    val wav = MicRecorder.recordWav(12_000, { stopRequested }, { level = it })
                    recording = false
                    status = "Looking for a match…"
                    val hit = AcrCloudClient.identify(wav, creds)
                    if (hit == null) {
                        status = "No match. Try humming the chorus a bit more clearly."
                    } else {
                        onSearch(hit.query)
                        onDismiss()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = e.message ?: "Something went wrong."
            } finally {
                busy = false
                recording = false
                level = 0f
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else status = "Microphone permission is needed to listen."
    }

    fun onMicTap() {
        if (busy) {
            if (recording) stopRequested = true else { job?.cancel(); status = "" }
            return
        }
        if (!singMode && !creds.isComplete) { editingKeys = true; return }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val pulse by animateFloatAsState(1f + level * 0.35f, label = "micPulse")

    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Hum or sing", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = singMode,
                    onClick = { if (!busy) { singMode = true; status = "" } },
                    label = { Text("Sing (lyrics)") },
                )
                FilterChip(
                    selected = !singMode,
                    onClick = { if (!busy) { singMode = false; status = "" } },
                    label = { Text("Hum (melody)") },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (singMode) {
                    "Sing a few words of the song. They're turned into text and searched."
                } else {
                    "Hum or sing the tune for about 10 seconds. It's matched to a song by ACRCloud."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onMicTap() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (busy) "Stop" else "Start listening",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = status.ifEmpty { "Tap the mic to start" },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            if (!singMode && (editingKeys || !creds.isComplete)) {
                Spacer(Modifier.height(16.dp))
                var host by remember { mutableStateOf(creds.host) }
                var key by remember { mutableStateOf(creds.accessKey) }
                var secret by remember { mutableStateOf(creds.accessSecret) }
                Text(
                    "Hum mode needs your own ACRCloud project (they have a free trial):\n" +
                        "1. Open the ACRCloud website below and sign up.\n" +
                        "2. Create a project: Audio & Video Recognition, and turn on humming.\n" +
                        "3. Copy its Host, Access Key and Access Secret into the boxes here.\n" +
                        "They stay on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { uriHandler.openUri("https://console.acrcloud.com") }) {
                    Text("Open ACRCloud website to get keys")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(host, { host = it }, label = { Text("Host (e.g. identify-eu-west-1.acrcloud.com)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("Access key") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("Access secret") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val c = AcrCredentials(host.trim(), key.trim(), secret.trim())
                        RecognitionPrefs.save(context, c)
                        creds = c
                        editingKeys = false
                        status = if (c.isComplete) "Saved. Tap the mic and hum." else status
                    },
                    enabled = host.isNotBlank() && key.isNotBlank() && secret.isNotBlank(),
                ) { Text("Save keys") }
            } else if (!singMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editingKeys = true }) { Text("Change keys") }
                    TextButton(onClick = { uriHandler.openUri("https://console.acrcloud.com") }) { Text("ACRCloud website") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
