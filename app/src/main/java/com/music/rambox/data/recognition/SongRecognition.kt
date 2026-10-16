package com.music.rambox.data.recognition

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Hum / sing recognition.
 *
 * Two independent routes, because they solve different problems:
 *
 *  - **Sing** ([SpeechLyrics]): the phone's own speech recogniser turns the
 *    words you sing into text, and that text is searched like any other query.
 *    No account and no key; it needs actual lyrics, not a tune.
 *  - **Hum** ([MicRecorder] + [AcrCloudClient]): a few seconds of audio from the
 *    microphone are sent to ACRCloud, which matches melodies (humming) as well
 *    as recorded music. It needs the user's own ACRCloud project credentials,
 *    which live on the device only — see [RecognitionPrefs].
 */
data class Recognized(val title: String, val artist: String) {
    /** The text to hand the app's search. */
    val query: String get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
}

/** Credentials of an ACRCloud project, entered once in the Hum sheet. */
data class AcrCredentials(val host: String, val accessKey: String, val accessSecret: String) {
    val isComplete: Boolean get() = host.isNotBlank() && accessKey.isNotBlank() && accessSecret.isNotBlank()
}

object RecognitionPrefs {
    private const val FILE = "rambox_recognition"

    fun load(context: Context): AcrCredentials {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return AcrCredentials(
            host = p.getString("acr_host", "").orEmpty(),
            accessKey = p.getString("acr_key", "").orEmpty(),
            accessSecret = p.getString("acr_secret", "").orEmpty(),
        )
    }

    fun save(context: Context, c: AcrCredentials) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("acr_host", c.host.trim())
            .putString("acr_key", c.accessKey.trim())
            .putString("acr_secret", c.accessSecret.trim())
            .apply()
    }
}

/** Records the microphone as 16 kHz mono PCM and hands it back as a WAV file. */
object MicRecorder {
    private const val RATE = 16_000

    /**
     * Records until [maxMillis] pass, [stopRequested] turns true, or the calling
     * coroutine is cancelled. The caller must already hold RECORD_AUDIO.
     * [onLevel] gets a 0..1 loudness about ten times a second, for the pulse.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordWav(
        maxMillis: Long,
        stopRequested: () -> Boolean,
        onLevel: (Float) -> Unit,
    ): ByteArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "This device can't record audio." }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "The microphone is busy or unavailable." }
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 10)
        val started = System.currentTimeMillis()
        try {
            rec.startRecording()
            while (coroutineContext.isActive && !stopRequested() &&
                System.currentTimeMillis() - started < maxMillis
            ) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until n) { bb.putShort(buf[i]); sum += buf[i].toDouble() * buf[i] }
                pcm.write(bb.array())
                onLevel((sqrt(sum / n) / 6000.0).coerceIn(0.0, 1.0).toFloat())
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        wav(pcm.toByteArray())
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}

/** ACRCloud's identify endpoint: signed multipart upload, JSON back. */
object AcrCloudClient {
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The matched song, or null when ACRCloud heard nothing it knew. */
    suspend fun identify(wav: ByteArray, c: AcrCredentials): Recognized? = withContext(Dispatchers.IO) {
        val host = c.host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val ts = (System.currentTimeMillis() / 1000).toString()
        val toSign = "POST\n/v1/identify\n${c.accessKey.trim()}\naudio\n1\n$ts"
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(c.accessSecret.trim().toByteArray(), "HmacSHA1"))
        }
        val signature = Base64.encodeToString(mac.doFinal(toSign.toByteArray()), Base64.NO_WRAP)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("access_key", c.accessKey.trim())
            .addFormDataPart("sample_bytes", wav.size.toString())
            .addFormDataPart("timestamp", ts)
            .addFormDataPart("signature", signature)
            .addFormDataPart("data_type", "audio")
            .addFormDataPart("signature_version", "1")
            .addFormDataPart("sample", "hum.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url("https://$host/v1/identify").post(body).build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "ACRCloud answered ${resp.code}." }
            parse(text)
        }
    }

    internal fun parse(json: String): Recognized? {
        val root = JSONObject(json)
        val status = root.optJSONObject("status")
        val code = status?.optInt("code", -1) ?: -1
        if (code == 1001) return null                       // no result
        if (code != 0) error(status?.optString("msg").orEmpty().ifBlank { "ACRCloud error $code." })
        val meta = root.optJSONObject("metadata") ?: return null
        // Humming matches come back under "humming", recorded music under "music".
        for (key in listOf("humming", "music")) {
            val first = meta.optJSONArray(key)?.optJSONObject(0) ?: continue
            val title = first.optString("title")
            val artist = first.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            if (title.isNotBlank()) return Recognized(title, artist)
        }
        return null
    }
}

/** Sing mode: what the phone's speech recogniser hears in the lyrics. */
object SpeechLyrics {
    fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listens until the recogniser decides the singing has stopped and returns
     * the transcript, or "" if it caught nothing. [onPartial] gets the words as
     * they come in. Runs on the main thread, which SpeechRecognizer requires.
     */
    suspend fun listen(context: Context, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.Main) {
            check(isAvailable(context)) { "Speech recognition isn't available on this device." }
            suspendCancellableCoroutine { cont ->
                val rec = SpeechRecognizer.createSpeechRecognizer(context)
                var done = false
                fun finish(block: () -> Unit) {
                    if (done) return
                    done = true
                    rec.destroy()
                    block()
                }
                fun Bundle?.best(): String =
                    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults.best().takeIf { it.isNotBlank() }?.let(onPartial)
                    }
                    override fun onResults(results: Bundle?) =
                        finish { cont.resumeWith(Result.success(results.best())) }
                    override fun onError(error: Int) = finish {
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> cont.resumeWith(Result.success(""))
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                cont.resumeWith(Result.failure(IllegalStateException("Microphone permission is needed.")))
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                cont.resumeWith(Result.failure(IllegalStateException("The speech service needs an internet connection.")))
                            else -> cont.resumeWith(Result.failure(IllegalStateException("Speech recognition failed (code $error).")))
                        }
                    }
                })
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        if (!done) { done = true; rec.cancel(); rec.destroy() }
                    }
                }
                rec.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    },
                )
            }
        }
}
