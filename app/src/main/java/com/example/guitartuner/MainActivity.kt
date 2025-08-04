package com.example.guitartuner

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.Manifest
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Space
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min



private var audioRecord: AudioRecord? = null
private var isAudioRecording = false // To track AudioRecord state
private val audioRecordSampleRate = 44100
private val audioRecordChannelConfig = AudioFormat.CHANNEL_IN_MONO
private val audioRecordAudioFormat = AudioFormat.ENCODING_PCM_16BIT
private lateinit var audioRecordBuffer: ShortArray
private var audioRecordBufferSizeInBytes: Int = 0


private val guitarNotes = mapOf(

    65.41 to "C2",
    73.42 to "D2",
    82.41 to "E2",
    110.00 to "A2",
    146.83 to "D3",
    196.00 to "G3",
    246.94 to "B3",
    329.63 to "E4",
    87.31 to "F2",
    92.50 to "F#2",
    98.00 to "G2",
    103.83 to "G#2",
    116.54 to "A#2",
    123.47 to "B2",
    130.81 to "C3",
    138.59 to "C#3",
    155.56 to "D#3",
    164.81 to "E3",
    174.61 to "F3",
    185.00 to "F#3",
    207.65 to "G#3",
    220.00 to "A3",
    233.08 to "A#3",
    261.63 to "C4",
    277.18 to "C#4",
    293.66 to "D4",
    311.13 to "D#4",
    349.23 to "F4",
    369.99 to "F#4",
    392.00 to "G4",
    415.30 to "G#4",
    440.00 to "A4",
    466.16 to "A#4",
    493.88 to "B4"
)

private val standardTuning = mapOf(
    82.41 to "E2",
    110.00 to "A2",
    146.83 to "D3",
    196.00 to "G3",
    246.94 to "B3",
    329.63 to "E4")

private val dropD = mapOf(
    73.42 to "D2",
    110.00 to "A2",
    146.83 to "D3",
    196.00 to "G3",
    246.94 to "B3",
    329.63 to "E4"
)

fun autocorrelation(signal: FloatArray, sampleRate: Int): Float {
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

    for (i in minLag until min(result.size, maxLag)) {
        if (result[i] > peakValue) {
            peakValue = result[i]
            peakLag = i
        }
    }
    return if (peakLag == 0 || peakValue <= 0) 0f else sampleRate.toFloat() / peakLag
}

private fun findClosestNote(frequency: Float): String {
    var closestNote = "Unknown"
    var minDifference = Double.MAX_VALUE

    for ((noteFreq, noteName) in guitarNotes) {
        val difference = abs(frequency - noteFreq)
        if (difference < minDifference) {
            minDifference = difference
            closestNote = noteName
        }
    }

    return "$closestNote (${frequency.toInt()} Hz)"
}

fun getTargetFrequencyForNote(noteName: String): Float? {
    return dropD.entries.find { it.value.startsWith(noteName.substringBefore(" ")) }?.key?.toFloat()
}

@Composable
fun GuitarStringVisualizer(note: String, frequency: Float) {
    val targetFrequency = getTargetFrequencyForNote(note)
    // Calculate the difference in cents. 100 cents = 1 semitone.
    // A positive value means the current frequency is sharp, negative means flat.
    val centsDifference = if (targetFrequency != null && targetFrequency > 0 && frequency > 0) {
        1200 * log2(frequency / targetFrequency)
    } else 0f
    // More complex visualizations could involve animations based on frequency, etc.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Note: $note")
        Spacer(modifier = Modifier.height(8.dp))
        Text("Frequency: ${"%.2f".format(frequency)} Hz")
        Spacer(modifier = Modifier.height(16.dp))
        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)) {
            val centerY = size.height / 2f
            val meterWidth = size.width * 0.8f // Use 80% of the canvas width for the meter
            val meterStartX = (size.width - meterWidth) / 2f
            val meterEndX = meterStartX + meterWidth

            // Draw the horizontal meter line
            drawLine(
                color = Color.Gray,
                start = Offset(meterStartX, centerY),
                end = Offset(meterEndX, centerY),
                strokeWidth = 2f
            )

            // Draw the indicator
            // Map cents difference to a position on the meter.
            // Let's say +/- 50 cents maps to the edges of the meter.
            val maxCentsDisplay = 50f
            val normalizedPosition = (centsDifference.coerceIn(-maxCentsDisplay, maxCentsDisplay) / maxCentsDisplay)
            val indicatorX = meterStartX + meterWidth / 2f + (normalizedPosition * meterWidth / 2f)

            if (frequency > 0 && targetFrequency != null) {
                drawCircle(
                    color = if (abs(centsDifference) < 5) Color.Green else Color.Red, // Green if within +/- 5 cents
                    radius = 8f,
                    center = Offset(indicatorX, centerY)
                )
            }
        }
    }
}



class MainActivity : AppCompatActivity() {

    private lateinit var freqView: TextView
    private lateinit var noteView: TextView
    private lateinit var composeView: ComposeView

    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 100L
    private var audioRecordBufferSizeInShorts: Int = 0

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Make sure you have this layout file
        freqView = findViewById(R.id.freqView)
        noteView = findViewById(R.id.note_view)
        composeView = findViewById(R.id.compose_view) // Assuming you have a ComposeView in your layout

        val buttonToSecondActivity: Button = findViewById(R.id.button_to_second_activity) // Assuming your button has this ID in your layout

        buttonToSecondActivity.setOnClickListener {
            val intent = Intent(this, SecondActivity::class.java)
            startActivity(intent)
        }

        composeView.setContent {
            GuitarStringVisualizer(note = "N/A", frequency = 0f) // Initial state
        }

        startMicListeningWithAudioRecord(this)


    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMicListeningWithAudioRecord(context: MainActivity) {
        if (isAudioRecording) {
            Log.w("AudioRecordListener", "AudioRecord is already recording.")
            return
        }

        //val fixedSampleRate: Int = 4096

        //audioRecordBufferSizeInShorts = fixedSampleRate

        audioRecordBufferSizeInBytes = 4096 // tai 8192


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

            CoroutineScope(Dispatchers.IO).launch {
                while (this.isActive && isAudioRecording) {
                    val readSize = audioRecord?.read(audioRecordBuffer, 0, audioRecordBuffer.size) ?: 0

                    if (readSize > 0) {
                        val floatSamples = convertShortArrayToFloatArray(audioRecordBuffer.copyOfRange(0, readSize))

                        val fundamentalFrequency = autocorrelation(floatSamples, audioRecordSampleRate)
                        val note = findClosestNote(fundamentalFrequency)

                        withContext(Dispatchers.Main) {
                            // Update UI less frequently by adding a delay or checking against previous values

                            if (fundamentalFrequency > 0) {
                                freqView.text = "%.2f Hz".format(fundamentalFrequency)
                                noteView.text = note

                                composeView.setContent {
                                    GuitarStringVisualizer(note = note, frequency = fundamentalFrequency)
                                }
                            } else {
                                freqView.text = "- Hz"

                            }
                        }
                        delay(100) // Add a delay to reduce update frequency, e.g., 100ms
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

