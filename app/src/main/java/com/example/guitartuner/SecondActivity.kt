package com.example.guitartuner


import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import android.widget.TextView
import androidx.activity.result.launch
import androidx.annotation.RequiresPermission
import androidx.compose.ui.semantics.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

//AUDIO PAGE

private var audioRecord: AudioRecord? = null
private var recordingJob: Job? = null
private var isAudioRecording = false // To track AudioRecord state
private val audioRecordSampleRate = 44100
private val audioRecordChannelConfig = AudioFormat.CHANNEL_IN_MONO
private val audioRecordAudioFormat = AudioFormat.ENCODING_PCM_16BIT
private lateinit var audioRecordBuffer: ShortArray
private var audioRecordBufferSizeInBytes: Int = 0


fun startRecording() {
    println("Permission granted")
}

//Autocorrelation
fun autocorrelate(signal: FloatArray, sampleRate: Int): Float {
    val size = signal.size
    if (size == 0) return 0f // Avoid division by zero or errors on empty signal

    val result = FloatArray(size)

    for (lag in 0 until size) {
        var sum = 0f
        for (i in 0 until size - lag) {
            sum += signal[i] * signal[i + lag]
        }
        result[lag] = sum
    }

    var peakLag = 0
    val minLag = sampleRate / 1000 // Example: Don't detect frequencies above 1kHz as fundamental
    val maxLag = sampleRate / 50   // Example: Don't detect frequencies below 50Hz as fundamental

    var peakValue = Float.MIN_VALUE
    // Ensure the loop bounds are valid
    for (i in minLag until kotlin.math.min(result.size, maxLag)) {
        if (result[i] > peakValue) {
            peakValue = result[i]
            peakLag = i
        }
    }
    // Log.d("Autocorrelate", "Peak lag: $peakLag, Peak Value: $peakValue, Result array size: ${result.size}")
    return if (peakLag == 0 || peakValue <= 0) 0f else sampleRate.toFloat() / peakLag
}

// Average Magnitude Difference Function (AMDF)
fun amdf(signal: FloatArray, sampleRate: Int): Float {
    val size = signal.size
    if (size == 0) return 0f

    val result = FloatArray(size)

    for (lag in 0 until size) {
        var sum = 0f
        for (i in 0 until size - lag) {
            sum += abs(signal[i] - signal[i + lag])
        }
        result[lag] = sum
    }

    var minLag = 0
    val minLagLimit = sampleRate / 1000
    val maxLagLimit = sampleRate / 50

    var minValue = Float.MAX_VALUE
    for (i in minLagLimit until kotlin.math.min(result.size, maxLagLimit)) {
        if (result[i] < minValue) {
            minValue = result[i]
            minLag = i
        }
    }
    return if (minLag == 0) 0f else sampleRate.toFloat() / minLag
}

// YIN Algorithm
fun yin(signal: FloatArray, sampleRate: Int, threshold: Float = 0.1f): Float {
    val size = signal.size
    if (size == 0) return 0f

    val yinBuffer = FloatArray(size / 2)

    // Step 1: Difference function
    for (tau in 0 until yinBuffer.size) {
        var sum = 0f
        for (i in 0 until yinBuffer.size) {
            val delta = signal[i] - signal[i + tau]
            sum += delta * delta
        }
        yinBuffer[tau] = sum
    }

    // Step 2: Cumulative mean normalized difference function
    yinBuffer[0] = 1f
    var runningSum = 0f
    for (tau in 1 until yinBuffer.size) {
        runningSum += yinBuffer[tau]
        yinBuffer[tau] *= tau / runningSum
    }

    // Step 3: Absolute threshold
    val minTau = sampleRate / 1000
    val maxTau = sampleRate / 50

    for (tau in minTau until kotlin.math.min(yinBuffer.size, maxTau)) {
        if (yinBuffer[tau] < threshold) {
            // Step 4: Parabolic interpolation
            var betterTau: Float = tau.toFloat()
            if (tau > 0 && tau < yinBuffer.size - 1) {
                val x0 = if (tau == 0) tau else tau - 1
                val x2 = if (tau == yinBuffer.size - 1) tau else tau + 1

                val a = (yinBuffer[x0] - 2 * yinBuffer[tau] + yinBuffer[x2]) / 2
                val b = (yinBuffer[x2] - yinBuffer[x0]) / 2
                if (a != 0f) {
                    betterTau = tau - (b / (2 * a))
                }
            }
            return sampleRate / betterTau
        }
    }

    return 0f
}

// McLeod Pitch Method (MPM)
fun mcleodPitchMethod(signal: FloatArray, sampleRate: Int): Float {
    val size = signal.size
    if (size == 0) return 0f

    val nsdf = FloatArray(size)

    // Calculate NSDF (Normalized Square Difference Function)
    for (tau in 0 until size) {
        var acf = 0f  // Autocorrelation
        var divisorM = 0f

        for (i in 0 until size - tau) {
            acf += signal[i] * signal[i + tau]
            divisorM += signal[i] * signal[i] + signal[i + tau] * signal[i + tau]
        }

        nsdf[tau] = if (divisorM == 0f) 0f else 2 * acf / divisorM
    }

    // Find peaks in NSDF
    val minTau = sampleRate / 1000
    val maxTau = sampleRate / 50

    var maxVal = 0f
    var determinedMaxTau = 0 // Renamed to avoid conflict with the outer scope maxTau

    for (tau in minTau until kotlin.math.min(nsdf.size - 1, maxTau)) { // Use the loop-local maxTau
        if (nsdf[tau] > maxVal && nsdf[tau] > nsdf[tau - 1] && nsdf[tau] > nsdf[tau + 1]) {
            maxVal = nsdf[tau]
            determinedMaxTau = tau
        }
    }

    return if (determinedMaxTau == 0 || maxVal < 0.8f) 0f else sampleRate.toFloat() / determinedMaxTau
}

fun convertShortArrayToFloatArray(shortArray: ShortArray): FloatArray {
    val floatArray = FloatArray(shortArray.size)
    for (i in shortArray.indices) {
        floatArray[i] = shortArray[i] / 32768.0f // Normalize to -1.0 to 1.0 for PCM_16BIT
    }
    return floatArray
}

// Fast Fourier Transform (simplified version)
fun fft(signal: FloatArray, sampleRate: Int): Float {
    val size = signal.size
    if (size == 0) return 0f

    // Find next power of 2
    val n = 1 shl (32 - Integer.numberOfLeadingZeros(size - 1))
    val paddedSignal = FloatArray(n)
    System.arraycopy(signal, 0, paddedSignal, 0, min(size, n))

    // Simple magnitude spectrum calculation
    val magnitudes = FloatArray(n / 2)

    for (k in 0 until n / 2) {
        var realSum = 0f
        var imagSum = 0f

        for (i in 0 until n) {
            val angle = -2.0 * PI * k * i / n
            realSum += paddedSignal[i] * cos(angle).toFloat()
            imagSum += paddedSignal[i] * sin(angle).toFloat()
        }

        magnitudes[k] = sqrt(realSum * realSum + imagSum * imagSum)
    }

    // Find peak frequency
    var maxMag = 0f
    var maxIndex = 0

    val minIndex = (50.0 * n / sampleRate).toInt()
    val maxIndexLimit = (1000.0 * n / sampleRate).toInt()

    for (i in minIndex until min(magnitudes.size, maxIndexLimit)) {
        if (magnitudes[i] > maxMag) {
            maxMag = magnitudes[i]
            maxIndex = i
        }
    }

    return if (maxIndex == 0) 0f else maxIndex * sampleRate.toFloat() / n
}

// Harmonic Product Spectrum
fun harmonicProductSpectrum(signal: FloatArray, sampleRate: Int): Float {
    val size = signal.size
    if (size == 0) return 0f

    // Calculate FFT magnitudes first
    val n = 1 shl (32 - Integer.numberOfLeadingZeros(size - 1))
    val paddedSignal = FloatArray(n)
    System.arraycopy(signal, 0, paddedSignal, 0, min(size, n))

    val magnitudes = FloatArray(n / 2)

    for (k in 0 until n / 2) {
        var realSum = 0f
        var imagSum = 0f

        for (i in 0 until n) {
            val angle = -2.0 * PI * k * i / n
            realSum += paddedSignal[i] * cos(angle).toFloat()
            imagSum += paddedSignal[i] * sin(angle).toFloat()
        }

        magnitudes[k] = sqrt(realSum * realSum + imagSum * imagSum)
    }

    // Apply HPS (multiply harmonics)
    val hps = FloatArray(n / 8) // Reduce size for harmonic products
    val numHarmonics = 5

    for (i in 0 until hps.size) {
        hps[i] = magnitudes[i]

        for (harmonic in 2..numHarmonics) {
            val harmonicIndex = i * harmonic
            if (harmonicIndex < magnitudes.size) {
                hps[i] *= magnitudes[harmonicIndex]
            }
        }
    }

    // Find peak in HPS
    var maxHps = 0f
    var maxIndex = 0

    val minIndex = (50.0 * n / sampleRate).toInt()
    val maxIndexLimit = (1000.0 * n / sampleRate).toInt()

    for (i in minIndex until min(hps.size, maxIndexLimit)) {
        if (hps[i] > maxHps) {
            maxHps = hps[i]
            maxIndex = i
        }
    }

    return if (maxIndex == 0) 0f else maxIndex * sampleRate.toFloat() / n
}

enum class PitchDetectionMethod {
    AUTOCORRELATION,
    AMDF,
    YIN,
    MCLEOD,
    FFT,
    HPS
}

fun detectPitch(signal: FloatArray, sampleRate: Int, method: PitchDetectionMethod): Float {
    return when (method) {
        PitchDetectionMethod.AUTOCORRELATION -> autocorrelate(signal, sampleRate)
        PitchDetectionMethod.AMDF -> amdf(signal, sampleRate)
        PitchDetectionMethod.YIN -> yin(signal, sampleRate)
        PitchDetectionMethod.MCLEOD -> mcleodPitchMethod(signal, sampleRate)
        PitchDetectionMethod.FFT -> fft(signal, sampleRate)
        PitchDetectionMethod.HPS -> harmonicProductSpectrum(signal, sampleRate)
    }
}

class SecondActivity : AppCompatActivity() {

    private var recorder: MediaRecorder? = null
    private lateinit var micLevelBar: ProgressBar
    private lateinit var freqView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 100L
    private var currentMethod = PitchDetectionMethod.AUTOCORRELATION
    private var methodIndex = 0
    private val methods = PitchDetectionMethod.values()


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)

        micLevelBar = findViewById(R.id.mic_level_bar)
        freqView = findViewById(R.id.freq_view)
        val methodButton: Button = findViewById(R.id.method_button)

        val buttonToMainActivity: Button = findViewById(R.id.button_to_main_activity)

        buttonToMainActivity.setOnClickListener {

            val intent = Intent(this, MainActivity::class.java)

            startActivity(intent)
        }

        methodButton.setOnClickListener {
            methodIndex = (methodIndex + 1) % methods.size
            currentMethod = methods[methodIndex]
            Log.d("PitchDetection", "Switched to method: $currentMethod")
        }

        startMicListeningWithAudioRecord(this)

    }



    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMicListeningWithAudioRecord(context: SecondActivity) {
        if (isAudioRecording) {
            Log.w("AudioRecordListener", "AudioRecord is already recording.")
            return
        }

        // Stop and release any previous MediaRecorder instance if it was being used
        // This is important if you are switching from the old MediaRecorder logic
        recorder?.stop()
        recorder?.release()
        recorder = null

        // Initialize buffer size for AudioRecord
        audioRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(
            audioRecordSampleRate,
            audioRecordChannelConfig,
            audioRecordAudioFormat
        )

        if (audioRecordBufferSizeInBytes == AudioRecord.ERROR_BAD_VALUE || audioRecordBufferSizeInBytes == AudioRecord.ERROR) {
            Log.e("AudioRecordListener", "Invalid AudioRecord parameters or unable to query capabilities.")
            // Handle error: e.g., show a message to the user
            return
        }
        audioRecordBuffer = ShortArray(audioRecordBufferSizeInBytes / 2) // Each Short is 2 bytes for PCM_16BIT
        Log.d("AudioRecordListener", "AudioRecord buffer size: ${audioRecordBuffer.size} shorts")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, // Or AudioSource.VOICE_RECOGNITION etc.
                audioRecordSampleRate,
                audioRecordChannelConfig,
                audioRecordAudioFormat,
                audioRecordBufferSizeInBytes
            )
        } catch (e: IllegalArgumentException) {
            Log.e("AudioRecordListener", "Failed to create AudioRecord instance", e)
            return
        }


        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecordListener", "AudioRecord not initialized")
            audioRecord?.release()
            audioRecord = null
            return
        }

        try {
            audioRecord?.startRecording()
            isAudioRecording = true
            Log.d("AudioRecordListener", "AudioRecord started successfully.")

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive && isAudioRecording) {
                    val readSize = audioRecord?.read(audioRecordBuffer, 0, audioRecordBuffer.size) ?: 0

                    if (readSize > 0) {
                        val floatSamples = convertShortArrayToFloatArray(audioRecordBuffer.copyOfRange(0, readSize))
                        val results = mutableMapOf<PitchDetectionMethod, Float>()
                        for (method in methods) {
                            results[method] = detectPitch(floatSamples, audioRecordSampleRate, method)
                        }
                        val fundamentalFrequency = results[currentMethod] ?: 0f

                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val currentSampleAbs = abs(audioRecordBuffer[i].toInt())
                            if (currentSampleAbs > maxAmplitude) {
                                maxAmplitude = currentSampleAbs
                            }
                        }
                        val level = (maxAmplitude.toDouble() / Short.MAX_VALUE * 100).toInt()

                        withContext(Dispatchers.Main) {
                            micLevelBar.progress = level


                            if (fundamentalFrequency > 0) {
                                freqView.text = "${currentMethod.name}: %.2f Hz".format(fundamentalFrequency)
                                Log.d("PitchDetection", "Method: $currentMethod, Frequency: $fundamentalFrequency Hz")

                                // Log all results for comparison
                                val resultsString = results.entries.joinToString(", ") { "${it.key.name}: %.1f".format(it.value) }
                                Log.d("PitchComparison", "All methods - $resultsString")
                            } else {
                                freqView.text = "${currentMethod.name}: -- Hz"
                            }
                        }
                    } else if (readSize < 0) {
                        Log.e("AudioRecordListener", "AudioRecord read error: $readSize")
                    }
                }
                Log.d("AudioRecordListener", "AudioRecord reading loop stopped.")
            }



        } catch (e: IllegalStateException) {
            Log.e("AudioRecordListener", "Failed to start AudioRecord", e)
            isAudioRecording = false
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) { // Catch any other unexpected error
            Log.e("AudioRecordListener", "An unexpected error occurred with AudioRecord", e)
            isAudioRecording = false
            audioRecord?.release()
            audioRecord = null
        }
    }

}