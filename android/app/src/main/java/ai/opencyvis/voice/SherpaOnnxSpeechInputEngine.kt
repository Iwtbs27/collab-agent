package ai.opencyvis.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class SherpaOnnxSpeechInputEngine(
    context: Context,
    private val numThreads: Int = DEFAULT_NUM_THREADS,
    private val downloadProgressListener: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
) : SpeechInputEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var shouldRecord = false

    @Volatile
    private var destroyed = false

    private var audioRecord: AudioRecord? = null
    private var recognizer: OnlineRecognizer? = null

    override fun startListening(listener: SpeechInputEngine.Listener) {
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError("Microphone permission is required")
            return
        }

        synchronized(lock) {
            if (captureJob?.isActive == true) return
            shouldRecord = true
            captureJob = scope.launch {
                runRecognition(listener)
            }
        }
    }

    override fun stopListening() {
        shouldRecord = false
        synchronized(lock) {
            audioRecord?.stopSafely()
            captureJob?.cancel()
            captureJob = null
        }
    }

    override fun destroy() {
        destroyed = true
        stopListening()
        scope.cancel()
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun runRecognition(listener: SpeechInputEngine.Listener) {
        var stream: OnlineStream? = null
        try {
            val recognizer = recognizer()
            if (!shouldRecord) return
            stream = recognizer.createStream("")
            if (!shouldRecord) return
            val recorder = createAudioRecord()
            synchronized(lock) {
                audioRecord = recorder
            }
            if (!shouldRecord) return

            recorder.startRecording()
            post { listener.onReady() }

            val shortBuffer = ShortArray(AUDIO_READ_SIZE)
            var lastPartial = ""

            while (shouldRecord) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        throw IOException("Audio recording error")
                    }
                    continue
                }

                val samples = FloatArray(read)
                for (i in 0 until read) {
                    samples[i] = shortBuffer[i] / PCM_16BIT_SCALE
                }
                stream.acceptWaveform(samples, SAMPLE_RATE)

                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }

                val partial = recognizer.getResult(stream).text.trim()
                if (partial.isNotEmpty() && partial != lastPartial) {
                    lastPartial = partial
                    post { listener.onPartialResult(partial) }
                }

                if (recognizer.isEndpoint(stream)) {
                    val finalText = recognizer.getResult(stream).text.trim()
                    shouldRecord = false
                    if (finalText.isNotEmpty()) {
                        post { listener.onFinalResult(finalText) }
                    } else {
                        post { listener.onEnd() }
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            if (shouldRecord) {
                Log.e(TAG, "Speech recognition failed", t)
                post { listener.onError(t.userMessage()) }
            }
        } finally {
            shouldRecord = false
            stream?.release()
            synchronized(lock) {
                audioRecord?.releaseSafely()
                audioRecord = null
                captureJob = null
            }
        }
    }

    private fun recognizer(): OnlineRecognizer {
        synchronized(lock) {
            recognizer?.let { return it }
        }

        val paths = ensureModelFiles()
        setNativeLibDir(paths.modelDir)
        val config = recognizerConfig(paths, numThreads)
        loadNativeLibraries()
        val created = OnlineRecognizer(config = config)

        synchronized(lock) {
            recognizer?.let {
                created.release()
                return it
            }
            if (destroyed) {
                created.release()
                throw IOException("Speech recognizer has been destroyed")
            }
            recognizer = created
            return created
        }
    }

    private fun ensureModelFiles(): SherpaOnnxModelFiles.Paths {
        val paths = SherpaOnnxModelFiles.paths(appContext.filesDir)
        if (SherpaOnnxModelFiles.allFilesPresent(appContext.filesDir)) return paths

        if (!paths.modelDir.exists() && !paths.modelDir.mkdirs()) {
            throw IOException("Unable to create ASR model directory")
        }

        AsrModelDownloader.downloadIfNeeded(
            url = SherpaOnnxModelFiles.downloadUrl(),
            targetDir = paths.modelDir,
            listener = downloadProgressListener?.let { callback ->
                AsrModelDownloader.ProgressListener { bytesRead, totalBytes ->
                    mainHandler.post { callback(bytesRead, totalBytes) }
                }
            }
        )

        return paths
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            throw IOException("Audio recording is unavailable")
        }
        val bufferBytes = minBufferBytes.coerceAtLeast(AUDIO_READ_SIZE * BYTES_PER_SHORT * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Audio recording initialization failed")
        }
        return recorder
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun Throwable.userMessage(): String {
        val message = message.orEmpty()
        return when {
            message.contains("Audio recording", ignoreCase = true) -> message
            message.contains("permission", ignoreCase = true) -> "Microphone permission is required"
            else -> "Speech recognition failed: ${message.ifBlank { javaClass.simpleName }}"
        }
    }

    private fun AudioRecord.stopSafely() {
        runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
        }
    }

    private fun AudioRecord.releaseSafely() {
        stopSafely()
        release()
    }

    internal companion object {
        private const val TAG = "SherpaSpeechInput"
        const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val DEFAULT_NUM_THREADS = 1
        private const val AUDIO_READ_SIZE = 1600
        private const val BYTES_PER_SHORT = 2
        private const val PCM_16BIT_SCALE = 32768.0f
        const val MODEL_TYPE = "zipformer"

        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibDir: File? = null

        fun setNativeLibDir(dir: File) {
            nativeLibDir = dir
        }

        private fun loadNativeLibraries() {
            if (nativeLibrariesLoaded) return
            synchronized(this) {
                if (nativeLibrariesLoaded) return
                val libDir = nativeLibDir
                if (libDir != null) {
                    System.load(File(libDir, "libonnxruntime.so").absolutePath)
                    System.load(File(libDir, "libsherpa-onnx-jni.so").absolutePath)
                } else {
                    System.loadLibrary("onnxruntime")
                    System.loadLibrary("sherpa-onnx-jni")
                }
                nativeLibrariesLoaded = true
            }
        }

        fun recognizerConfig(
            paths: SherpaOnnxModelFiles.Paths,
            numThreads: Int
        ): OnlineRecognizerConfig =
            OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = paths.encoder,
                        decoder = paths.decoder,
                        joiner = paths.joiner
                    ),
                    tokens = paths.tokens,
                    numThreads = numThreads.coerceIn(1, 2),
                    provider = "cpu",
                    modelType = MODEL_TYPE
                ),
                endpointConfig = EndpointConfig(),
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )
    }
}
