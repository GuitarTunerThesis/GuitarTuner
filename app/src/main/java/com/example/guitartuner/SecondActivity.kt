package com.example.guitartuner


import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.TextView
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import org.jtransforms.fft.DoubleFFT_1D

//Page for testing algorithms, left in just in case.


private var audioRecord: AudioRecord? = null
private var recordingJob: Job? = null
private var isAudioRecording = false
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
    if (size == 0) return 0f

    val result = FloatArray(size)

    for (lag in 0 until size) {
        var sum = 0f
        for (i in 0 until size - lag) {
            sum += signal[i] * signal[i + lag]
        }
        result[lag] = sum
    }

    var peakLag = 0
    val minLag = sampleRate / 1000
    val maxLag = sampleRate / 50

    var peakValue = Float.MIN_VALUE

    for (i in minLag until kotlin.math.min(result.size, maxLag)) {
        if (result[i] > peakValue) {
            peakValue = result[i]
            peakLag = i
        }
    }
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

fun zcr(signal: FloatArray, sampleRate: Int): Float {
    var zeroCrossings = 0
    for (i in 1 until signal.size) {
        val prev = signal[i - 1]
        val current = signal[i]
        if ((prev >= 0 && current < 0) || (prev < 0 && current >= 0)) {
            zeroCrossings++
        }
    }
    val durationInSeconds = signal.size.toFloat() / sampleRate
    return (zeroCrossings / (2 * durationInSeconds))
}

// YIN Algorithm
fun yin(signal: FloatArray, sampleRate: Int): Float {
    val bufferSize = signal.size
    val halfBufferSize = bufferSize / 2
    val yinBuffer = FloatArray(halfBufferSize)
    val threshold = 0.15f

    for (tau in 0 until halfBufferSize) {
        yinBuffer[tau] = 0f
        for (j in 0 until halfBufferSize) {
            if (j + tau < bufferSize) {
                val delta = signal[j] - signal[j + tau]
                yinBuffer[tau] += delta * delta
            }
        }
    }

    yinBuffer[0] = 1f
    var runningSum = 0f

    for (tau in 1 until halfBufferSize) {
        runningSum += yinBuffer[tau]
        if (runningSum > 0) {
            yinBuffer[tau] *= tau / runningSum
        }
    }


    var tau = -1
    for (t in 2 until halfBufferSize - 1) {
        if (yinBuffer[t] < threshold) {
            if (yinBuffer[t] < yinBuffer[t - 1] && yinBuffer[t] <= yinBuffer[t + 1]) {
                tau = t
                break
            }
        }
    }

    if (tau == -1) {
        var minTau = 2
        var minVal = yinBuffer[2]
        for (t in 3 until halfBufferSize - 1) {
            if (yinBuffer[t] < minVal) {
                minVal = yinBuffer[t]
                minTau = t
            }
        }

        if (minVal < 0.8f) {
            tau = minTau
        }
    }


    val betterTau = if (tau > 1 && tau < halfBufferSize - 1) {
        val s0 = yinBuffer[tau - 1]
        val s1 = yinBuffer[tau]
        val s2 = yinBuffer[tau + 1]

        val a = (s0 - 2 * s1 + s2) / 2f
        val b = (s2 - s0) / 2f

        if (a != 0f) {
            val correction = -b / (2 * a)
            tau + correction
        } else {
            tau.toFloat()
        }
    } else {
        tau.toFloat()
    }


    return if (betterTau > 2f && betterTau < halfBufferSize) {
        val frequency = sampleRate / betterTau

        if (frequency > 50f && frequency < 4000f) {
            frequency
        } else {
            0f
        }
    } else {
        0f
    }
}

// McLeod Pitch Method (MPM)
fun mcleodPitchMethod(signal: FloatArray, sampleRate: Int): Float {
    val size = signal.size
    if (size == 0) return 0f

    val nsdf = FloatArray(size)


    for (tau in 0 until size) {
        var acf = 0f
        var divisorM = 0f

        for (i in 0 until size - tau) {
            acf += signal[i] * signal[i + tau]
            divisorM += signal[i] * signal[i] + signal[i + tau] * signal[i + tau]
        }

        nsdf[tau] = if (divisorM == 0f) 0f else 2 * acf / divisorM
    }


    val minTau = sampleRate / 1000
    val maxTau = sampleRate / 50

    var maxVal = 0f
    var determinedMaxTau = 0

    for (tau in minTau until kotlin.math.min(nsdf.size - 1, maxTau)) {
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
        floatArray[i] = shortArray[i] / 32768.0f
    }
    return floatArray
}


fun fftFreq(samples: FloatArray, sampleRate: Int): Double {

    val windowedSamples = applyHammingWindow(samples)

    val fft = DoubleFFT_1D(windowedSamples.size.toLong())

    val fftData = DoubleArray(windowedSamples.size * 2)
    for (i in windowedSamples.indices) {
        fftData[i * 2] = windowedSamples[i].toDouble()
        fftData[i * 2 + 1] = 0.0
    }

    fft.complexForward(fftData)

    var maxMagnitude = 0.0
    var maxIndex = 1

    for (i in 1 until windowedSamples.size / 2) {
        val real = fftData[i * 2]
        val imag = fftData[i * 2 + 1]
        val magnitude = sqrt(real * real + imag * imag)

        if (magnitude > maxMagnitude) {
            maxMagnitude = magnitude
            maxIndex = i
        }
    }


    return maxIndex * sampleRate.toDouble() / windowedSamples.size
}

private fun applyHammingWindow(samples: FloatArray): FloatArray {
    val n = samples.size
    val windowed = FloatArray(n)

    for (i in 0 until n) {
        val window = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))
        windowed[i] = samples[i] * window.toFloat()
    }

    return windowed
}


// Harmonic Product Spectrum
fun harmonicProductSpectrum(signal: FloatArray, sampleRate: Int): Float {
    val n = signal.size
    val fft = DoubleFFT_1D(n.toLong())


    val fftData = DoubleArray(n * 2)
    for (i in signal.indices) {
        fftData[i * 2] = signal[i].toDouble()
        fftData[i * 2 + 1] = 0.0
    }


    fft.complexForward(fftData)


    val magnitude = FloatArray(n / 2)
    for (i in 0 until n / 2) {
        val re = fftData[i * 2]
        val im = fftData[i * 2 + 1]
        magnitude[i] = sqrt(re * re + im * im).toFloat()
    }


    val hpsSize = minOf(n / 16, magnitude.size / 5)
    val hps = FloatArray(hpsSize)
    for (i in hps.indices) {
        hps[i] = magnitude[i]

        for (harmonic in 2..5) {
            val idx = i * harmonic
            if (idx < magnitude.size) {
                hps[i] *= magnitude[idx]
            }
        }
        hps[i] *= (1f / (1f + i * 0.001f))
    }

    // Find peak frequency
    val minFreq = 50f
    val minIdx = Math.max(1, (minFreq * n / sampleRate).toInt())

    var maxIdx = minIdx
    var maxVal = 0f
    for (i in minIdx until hps.size) {
        if (hps[i] > maxVal) {
            maxVal = hps[i]
            maxIdx = i
        }
    }

    val freq = if (maxIdx > 0 && maxIdx < hps.size - 1) {
        val y1 = hps[maxIdx - 1]
        val y2 = hps[maxIdx]
        val y3 = hps[maxIdx + 1]
        val a = (y1 - 2 * y2 + y3) / 2f
        val b = (y3 - y1) / 2f
        val correction = if (a != 0f) -b / (2 * a) else 0f
        ((maxIdx + correction) * sampleRate.toFloat()) / n
    } else {
        (maxIdx * sampleRate.toFloat()) / n
    }

    return freq
}
enum class PitchDetectionMethod {
    AUTOCORRELATION,
    AMDF,
    YIN,
    MCLEOD,
    //FFT,
    HPS
}

fun detectPitch(signal: FloatArray, sampleRate: Int, method: PitchDetectionMethod): Float {
    return when (method) {
        PitchDetectionMethod.AUTOCORRELATION -> autocorrelate(signal, sampleRate)
        PitchDetectionMethod.AMDF -> amdf(signal, sampleRate)
        PitchDetectionMethod.YIN -> yin(signal, sampleRate)
        PitchDetectionMethod.MCLEOD -> mcleodPitchMethod(signal, sampleRate)
        //PitchDetectionMethod.FFT -> fftFreq(signal, sampleRate)
        PitchDetectionMethod.HPS -> harmonicProductSpectrum(signal, sampleRate)
    }
}

class SecondActivity : AppCompatActivity() {

    private lateinit var micLevelBar: ProgressBar
    private lateinit var freqView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 100L
    private var currentMethod = PitchDetectionMethod.AUTOCORRELATION
    private var methodIndex = 0
    private val methods = PitchDetectionMethod.values()
    private var audioRecordBufferSizeInShorts: Int = 0


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


        startMicListeningWithAudioRecord(this)

    }



    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMicListeningWithAudioRecord(context: SecondActivity) {
        if (isAudioRecording) {
            Log.w("AudioRecordListener", "AudioRecord is already recording.")
            return
        }

        val fixedSampleRate: Int = 4096

        audioRecordBufferSizeInShorts = fixedSampleRate

        audioRecordBufferSizeInBytes = audioRecordBufferSizeInShorts * 2

        if (audioRecordBufferSizeInBytes == AudioRecord.ERROR_BAD_VALUE || audioRecordBufferSizeInBytes == AudioRecord.ERROR) {
            Log.e("AudioRecordListener", "Invalid AudioRecord parameters or unable to query capabilities.")
            return
        }
        audioRecordBuffer = ShortArray(audioRecordBufferSizeInBytes / 2)
        Log.d("AudioRecordListener", "AudioRecord buffer size: ${audioRecordBuffer.size} shorts")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
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
                    //Uncomment one method below to test different algorithms
                    if (readSize > 0) {
                        val floatSamples = convertShortArrayToFloatArray(audioRecordBuffer.copyOfRange(0, readSize))
                        //val results = mutableMapOf<PitchDetectionMethod, Float>()
                        /*for (method in methods) {
                            results[method] = detectPitch(floatSamples, audioRecordSampleRate, method)
                        }*/
                        //val fundamentalFrequency = results[currentMethod] ?: 0f
                        //val fundamentalFrequency = autocorrelate(floatSamples, audioRecordSampleRate)
                        //val fundamentalFrequency = amdf(floatSamples, audioRecordSampleRate)
                        //val fundamentalFrequency = zcr(floatSamples, audioRecordSampleRate)
                        val fundamentalFrequency = yin(floatSamples, audioRecordSampleRate)
                        //val fundamentalFrequency = mcleodPitchMethod(floatSamples, audioRecordSampleRate)
                        //val fundamentalFrequency = fftFreq(floatSamples, audioRecordSampleRate)
                        //val fundamentalFrequency = harmonicProductSpectrum(floatSamples, audioRecordSampleRate)
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
        } catch (e: Exception) {
            Log.e("AudioRecordListener", "An unexpected error occurred with AudioRecord", e)
            isAudioRecording = false
            audioRecord?.release()
            audioRecord = null
        }
    }

}