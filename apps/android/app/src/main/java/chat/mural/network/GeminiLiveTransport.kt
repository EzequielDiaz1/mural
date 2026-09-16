package chat.mural.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import chat.mural.R
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString

/** PCM microphone/audio streaming for Google's Live WebSocket API. No audio is written to disk. */
class GeminiLiveTransport(context: Context, private val scope: CoroutineScope) : VoiceTransport {
    override var onEvent: ((JsonObject) -> Unit)? = null
    override var onFailure: ((String) -> Unit)? = null
    override var onLevels: ((Double, Double) -> Unit)? = null
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).build()
    @Volatile private var active: Attempt? = null
    private var cleanupJob: Job? = null
    override val started get() = active?.ready?.isCompleted == true
    override val isMuted get() = active?.muted ?: false

    override suspend fun connect(api: LiveSessionProvider, instructions: String, history: JsonArray, language: String?) {
        disconnect()
        cleanupJob?.join()
        val gemini = api as? GeminiAPIClient ?: throw IOException("Gemini transport requires Gemini credentials.")
        val request = gemini.liveRequest()
        val a = Attempt()
        active = a
        try {
            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw IOException(app.getString(R.string.error_transport_microphone))
            try {
                claimFocus(a)
                // Cleanup waits for allocation before releasing the native resources.
                withContext(NonCancellable + Dispatchers.IO) { createAudio(a) }
            } finally { a.allocated.complete(Unit) }
            currentCoroutineContext().ensureActive()
            a.socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (active !== a) { webSocket.cancel(); return }
                    if (!webSocket.send(setup(instructions).toString())) fail(a, R.string.error_transport_connection)
                }
                override fun onMessage(webSocket: WebSocket, text: String) = receive(a, text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > MAX_EVENT_BYTES) fail(a, R.string.error_transport_invalid_event)
                    else receive(a, bytes.utf8())
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val message = when (response?.code) {
                        400, 401, 403 -> app.getString(R.string.gemini_http_auth_error, response.code)
                        429 -> app.getString(R.string.gemini_http_quota_error, response.code)
                        null -> app.getString(R.string.gemini_network_error, t.javaClass.simpleName)
                        else -> app.getString(R.string.gemini_http_error, response.code)
                    }
                    fail(a, message)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    fail(a, closeMessage(code))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(a, closeMessage(code))
            })
            withTimeout(30_000) { a.ready.await() }
        } catch (e: Throwable) {
            a.allocated.complete(Unit)
            if (active === a) disconnect()
            throw e
        }
    }

    private fun claimFocus(a: Attempt) {
        if (manager.mode != AudioManager.MODE_NORMAL) throw IOException(app.getString(R.string.error_transport_audio_focus))
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes()).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) fail(a, R.string.error_transport_audio_interrupted)
            }, Handler(Looper.getMainLooper())).build()
        a.focus = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw IOException(app.getString(R.string.error_transport_audio_focus))
        a.ownsFocus = true
        a.previousSpeaker = manager.isSpeakerphoneOn
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        a.ownsMode = true
        @Suppress("DEPRECATION")
        manager.isSpeakerphoneOn = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudio(a: Attempt) {
        val inputSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outputSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (inputSize <= 0 || outputSize <= 0) throw IOException(app.getString(R.string.error_transport_audio_stopped))
        a.recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inputSize * 2, 6400))
        a.player = AudioTrack.Builder().setAudioAttributes(attributes())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(outputSize * 2, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (a.recorder?.state != AudioRecord.STATE_INITIALIZED || a.player?.state != AudioTrack.STATE_INITIALIZED)
            throw IOException(app.getString(R.string.error_transport_audio_stopped))
        if (AcousticEchoCanceler.isAvailable()) a.echo = AcousticEchoCanceler.create(a.recorder!!.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) a.noise = NoiseSuppressor.create(a.recorder!!.audioSessionId)?.apply { enabled = true }
    }

    private fun startAudio(a: Attempt) {
        a.input = cleanupScope.launch {
            try {
                val recorder = a.recorder ?: return@launch
                recorder.startRecording()
                val bytes = ByteArray(640) // 20 ms, mono PCM16 at 16 kHz.
                while (isActive && active === a) {
                    val n = recorder.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { fail(a, R.string.error_transport_audio_stopped); break }
                    if (!a.muted) {
                        if ((a.socket?.queueSize() ?: 0) > 256_000L) { fail(a, R.string.error_transport_network_lost); break }
                        val payload = buildJsonObject { put("realtimeInput", buildJsonObject {
                            put("audio", buildJsonObject {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", Base64.encodeToString(bytes, 0, n, Base64.NO_WRAP))
                            })
                        }) }
                        if (a.socket?.send(payload.toString()) != true) { fail(a, R.string.error_transport_network_lost); break }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - a.lastMeter >= 100) {
                        a.lastMeter = now
                        levels(a, if (a.muted) 0.0 else level(bytes, n), a.outputLevel)
                    }
                }
            } catch (_: Exception) { fail(a, R.string.error_transport_audio_stopped) }
        }
        a.output = cleanupScope.launch {
            try {
                val player = a.player ?: return@launch
                player.play()
                for (frame in a.audio) {
                    var offset = 0
                    while (offset < frame.bytes.size && isActive && active === a && frame.epoch == a.epoch.get()) {
                        val n = synchronized(a.playbackLock) {
                            if (frame.epoch != a.epoch.get()) 0 else
                                player.write(frame.bytes, offset, minOf(960, frame.bytes.size - offset), AudioTrack.WRITE_BLOCKING)
                        }
                        if (n < 0) throw IOException("Audio playback failed")
                        if (n == 0) break
                        offset += n
                        a.outputLevel = level(frame.bytes, frame.bytes.size)
                    }
                    a.outputLevel = 0.0
                }
            } catch (_: Exception) { fail(a, R.string.error_transport_audio_stopped) }
        }
    }

    private fun receive(a: Attempt, payload: String) {
        if (active !== a) return
        if (payload.length > MAX_EVENT_BYTES) { fail(a, R.string.error_transport_invalid_event); return }
        scope.launch {
            if (active !== a) return@launch
            try {
                val message = Json.parseToJsonElement(payload).jsonObject
                if (message["error"] != null) { fail(a, R.string.error_http_403_404); return@launch }
                if (message["setupComplete"] != null && !a.ready.isCompleted) {
                    startAudio(a)
                    a.ready.complete(Unit)
                    onEvent?.invoke(buildJsonObject { put("type", "session.started") })
                }
                val content = message["serverContent"] as? JsonObject ?: return@launch
                if (content["interrupted"] == JsonPrimitive(true)) {
                    a.epoch.incrementAndGet()
                    // Flush only on the audio worker: a blocking write must never hold up Compose.
                    cleanupScope.launch { synchronized(a.playbackLock) {
                        if (active === a) runCatching { a.player?.pause(); a.player?.flush(); a.player?.play() }
                    } }
                }
                for (part in ((content["modelTurn"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()) {
                    val data = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: continue
                    if (data["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/pcm") != true) continue
                    val bytes = Base64.decode(data["data"]!!.jsonPrimitive.content, Base64.DEFAULT)
                    if (bytes.size > 192_000 || bytes.size % 2 != 0 || !a.audio.trySend(Frame(bytes, a.epoch.get())).isSuccess) {
                        fail(a, R.string.error_transport_audio_stopped); return@launch
                    }
                }
                transcript(a, content["inputTranscription"] as? JsonObject, "input")
                transcript(a, content["outputTranscription"] as? JsonObject, "output")
            } catch (_: Exception) { fail(a, R.string.error_transport_invalid_event) }
        }
    }

    private fun transcript(a: Attempt, value: JsonObject?, kind: String) {
        val text = (value?.get("text") as? JsonPrimitive)?.contentOrNull ?: return
        if (text.isBlank()) return
        val time = (SystemClock.elapsedRealtime() - a.start).coerceIn(0, Int.MAX_VALUE.toLong() - 1).toInt()
        onEvent?.invoke(buildJsonObject {
            put("type", "session.${kind}_transcript.delta"); put("delta", text)
            put("start_ms", time); put("end_ms", time + 1); put("event_id", UUID.randomUUID().toString())
        })
    }

    override fun send(event: JsonObject): Boolean {
        val a = active ?: return false
        if (!a.ready.isCompleted) return false
        return a.socket?.send(commandPayload(event)?.toString() ?: return false) == true
    }

    override fun mute(muted: Boolean) {
        val a = active ?: return
        a.muted = muted
        if (muted) a.socket?.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}")
    }

    override fun close() {
        val a = active ?: return
        val seconds = (SystemClock.elapsedRealtime() - a.start) / 1000.0
        disconnect()
        onEvent?.invoke(buildJsonObject { put("type", "session.closed")
            put("usage", buildJsonObject { put("seconds", seconds) }) })
    }

    override fun disconnect() {
        val a = active ?: return
        active = null
        a.socket?.cancel(); a.ready.cancel(); a.audio.close()
        a.input?.cancel(); a.output?.cancel()
        val previous = cleanupJob
        cleanupJob = cleanupScope.launch {
            previous?.join()
            a.allocated.await()
            runCatching { a.recorder?.stop() }
            a.input?.join(); a.output?.join()
            runCatching { a.echo?.release() }; runCatching { a.noise?.release() }
            runCatching { a.recorder?.release() }
            runCatching { a.player?.pause(); a.player?.flush(); a.player?.release() }
            if (a.ownsMode) {
                @Suppress("DEPRECATION")
                runCatching { manager.isSpeakerphoneOn = a.previousSpeaker; manager.mode = AudioManager.MODE_NORMAL }
            }
            if (a.ownsFocus) runCatching { a.focus?.let(manager::abandonAudioFocusRequest) }
        }
        onLevels?.invoke(0.0, 0.0)
    }

    private fun fail(a: Attempt, resource: Int) { scope.launch {
        if (active === a) { disconnect(); onFailure?.invoke(app.getString(resource)) }
    } }
    private fun fail(a: Attempt, message: String) { scope.launch {
        if (active === a) { disconnect(); onFailure?.invoke(message) }
    } }
    private fun closeMessage(code: Int): String = when (code) {
        1008 -> app.getString(R.string.gemini_ws_policy_error, code)
        1011, 1012, 1013 -> app.getString(R.string.gemini_ws_service_error, code)
        else -> app.getString(R.string.gemini_ws_closed, code)
    }
    private fun levels(a: Attempt, input: Double, output: Double) { scope.launch {
        if (active === a) onLevels?.invoke(input, output)
    } }
    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private data class Frame(val bytes: ByteArray, val epoch: Long)
    private class Attempt {
        val start = SystemClock.elapsedRealtime()
        val ready = CompletableDeferred<Unit>()
        val allocated = CompletableDeferred<Unit>()
        val audio = Channel<Frame>(32)
        val epoch = AtomicLong(0)
        val playbackLock = Any()
        @Volatile var muted = false
        @Volatile var outputLevel = 0.0
        var lastMeter = 0L
        var socket: WebSocket? = null
        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        var focus: AudioFocusRequest? = null
        var ownsFocus = false
        var ownsMode = false
        var previousSpeaker = false
        var input: Job? = null
        var output: Job? = null
    }

    companion object {
        internal fun commandPayload(event: JsonObject): JsonObject? {
            val text = (event["content"] as? JsonPrimitive)?.contentOrNull ?: return null
            val type = (event["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (type !in setOf("session.thinking.append", "session.instructions.append", "session.commentary.append")) return null
            return buildJsonObject {
                put("clientContent", buildJsonObject {
                    put("turns", buildJsonArray { add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
                    }) })
                    // Background assessment notes update context without cutting off the speaker.
                    put("turnComplete", type != "session.thinking.append")
                })
            }
        }
        private const val MAX_EVENT_BYTES = 524_288
        internal fun setup(instructions: String) = buildJsonObject { put("setup", buildJsonObject {
            put("model", "models/${GeminiAPIClient.LIVE_MODEL}")
            put("generationConfig", buildJsonObject { put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) }) })
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject {
                put("text", instructions + "\nNo client delegation tool is available in this voice session. For current facts, say you cannot verify them here. Never invent a search result.")
            }) }) })
            put("inputAudioTranscription", buildJsonObject {})
            put("outputAudioTranscription", buildJsonObject {})
        }) }
        private fun level(bytes: ByteArray, count: Int): Double {
            var peak = 0
            for (i in 0 until count - 1 step 2) {
                val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                peak = maxOf(peak, abs(sample))
            }
            return minOf(1.0, peak / 8192.0)
        }
    }
}
