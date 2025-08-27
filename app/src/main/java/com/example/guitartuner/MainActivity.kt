package com.example.guitartuner

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Button as ComposeButton
import androidx.core.content.edit
import com.example.guitartuner.TuningSet.Companion.KEY_STRING_1
import com.example.guitartuner.TuningSet.Companion.KEY_STRING_2
import com.example.guitartuner.TuningSet.Companion.KEY_STRING_3
import com.example.guitartuner.TuningSet.Companion.KEY_STRING_4
import com.example.guitartuner.TuningSet.Companion.KEY_STRING_5
import com.example.guitartuner.TuningSet.Companion.KEY_STRING_6
import kotlin.math.sqrt


private var audioRecord: AudioRecord? = null
private var isAudioRecording = false // To track AudioRecord state
private val audioRecordSampleRate = 44100
private val audioRecordChannelConfig = AudioFormat.CHANNEL_IN_MONO
private val audioRecordAudioFormat = AudioFormat.ENCODING_PCM_16BIT
private lateinit var audioRecordBuffer: ShortArray
private var audioRecordBufferSizeInBytes: Int = 0


private val guitarNotes = mapOf(
    61.74 to "B1",
    65.41 to "C2",
    69.30 to "C#2",
    73.42 to "D2",
    77.78 to "D#2",
    82.41 to "E2",
    87.31 to "F2",
    92.50 to "F#2",
    97.99 to "G2",
    103.83 to "G#2",
    110.00 to "A2",
    116.54 to "A#2",
    123.47 to "B2",
    130.81 to "C3",
    138.59 to "C#3",
    146.83 to "D3",
    155.56 to "D#3",
    164.81 to "E3",
    174.61 to "F3",
    185.00 to "F#3",
    196.00 to "G3",
    207.65 to "G#3",
    220.00 to "A3",
    233.08 to "A#3",
    246.94 to "B3",
    261.63 to "C4",
    277.18 to "C#4",
    293.66 to "D4",
    311.13 to "D#4",
    329.63 to "E4",
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

//val customTuning = loadCustomTuning(this)


fun loadCustomTuning(context: Context): Map<Double, String>? {
    val sharedPreferences = context.getSharedPreferences("GuitarTunerPrefs", Context.MODE_PRIVATE)
    val selectedNotes = listOf(
        sharedPreferences.getString(KEY_STRING_1, "E2"),
        sharedPreferences.getString(KEY_STRING_2, "A2"),
        sharedPreferences.getString(KEY_STRING_3, "D3"),
        sharedPreferences.getString(KEY_STRING_4, "G3"),
        sharedPreferences.getString(KEY_STRING_5, "B3"),
        sharedPreferences.getString(KEY_STRING_6, "E4")
    )

    // Build map of frequency → note name
    return selectedNotes.mapNotNull { noteName ->
        guitarNotes.entries.firstOrNull { it.value == noteName }?.let { entry ->
            entry.key to entry.value
        }
    }.toMap()
}

private val tunings = mapOf("Standard" to standardTuning, "Drop D" to dropD, "Open G" to standardTuning, "DADGAD" to standardTuning)

object TuningState{
    var selectedTuningName: String = "Standard"

    var allTunings: Map<String, Map<Double, String>> = emptyMap()
        private set // Allow external read, but only MainActivity (or a dedicated manager) should set this

    // Function to initialize or update all tunings
    // Context is needed here to load custom tuning
    fun initialize(context: Context) {
        val customTuningFromPrefs = loadCustomTuning(context) // Assuming loadCustomTuning is accessible

        // Build the complete map of tunings
        // Make sure standardTuning, dropD etc. are accessible here
        // (e.g., defined in this file, imported, or passed as parameters)
        val availableTunings = tunings.toMutableMap()


        if (customTuningFromPrefs != null && customTuningFromPrefs.isNotEmpty()) {
            availableTunings["Custom"] = customTuningFromPrefs
        } else {
            Log.w("TuningState", "Custom tuning was null or empty, not adding to list.")
        }

        allTunings = availableTunings.toMap() // Make it immutable after construction

        // Ensure selectedTuningName is valid, otherwise default to "Standard" or first available
        if (!allTunings.containsKey(selectedTuningName)) {
            selectedTuningName = allTunings.keys.firstOrNull() ?: "Standard"
            Log.w("TuningState", "Previously selected tuning not found, defaulted to $selectedTuningName")
        }
        Log.d("TuningState", "Initialized. Selected: $selectedTuningName. All: ${allTunings.keys}")
    }
}

fun autocorrelation(signal: FloatArray, sampleRate: Int): Float {
    val size = signal.size
    if (size == 0) return 0f

    val energy = rms(signal)
    if (energy < 0.01f){
        return 0f
    }

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

    val clarity = peakValue / result[0]
    if (peakLag == 0 || clarity < 0.1f) return 0f

    val frequency = sampleRate.toFloat() / peakLag
    return if (frequency in 50f..800f) frequency else 0f

}

fun rms(signal: FloatArray): Float {
    var sum = 0f
    for (value in signal) {
        sum += value * value
    }
    return sqrt(sum / signal.size)
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
    val currentAllTunings = TuningState.allTunings
    val tuningMap = currentAllTunings[TuningState.selectedTuningName]?: return null
    return tuningMap.entries.find { it.value.startsWith(noteName.substringBefore(" ")) }?.key?.toFloat()
}


@Composable
fun TuningSelect(selectedTuning: String, onTuningSelected: (String) -> Unit, tuningsMap: Map<String, Map<Double, String>>) {

    val tuningOptions = tuningsMap.keys.toList()
    var expanded by remember { mutableStateOf(false) }
    var selectedTuning by remember { mutableStateOf(TuningState.selectedTuningName) }

    Box(modifier = Modifier
        .fillMaxWidth()
        .background(Color.LightGray, RoundedCornerShape(4.dp))
        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
        .clickable { expanded = true }
        .padding(12.dp),
        contentAlignment = Alignment.Center)
        {
        Text(text = selectedTuning)


        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            tuningOptions.forEach { tuning ->
                DropdownMenuItem(
                    text = { Text(tuning) },
                    onClick = {
                        selectedTuning = tuning
                        TuningState.selectedTuningName = tuning
                        expanded = false
                    }
                )
            }
        }
    }
}


@Composable
fun GuitarStringVisualizer(note: String, frequency: Float) {
    val image = ImageBitmap.imageResource(id = R.drawable.indicator)
    val targetFrequency = getTargetFrequencyForNote(note)
    val centsDifference = if (targetFrequency != null && targetFrequency > 0 && frequency > 0) {
        1200 * log2(frequency / targetFrequency)
    } else 0f

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
            val meterWidth = size.width * 0.8f
            val meterStartX = (size.width - meterWidth) / 2f
            val meterEndX = meterStartX + meterWidth

            val backgroundHeight = 460f
            val backgroundPad = 110f
            val backgroundTopY = centerY - backgroundHeight / 2f


            drawRoundRect(
                color = Color(0xFFEFEFEF),
                topLeft = Offset(meterStartX-backgroundPad / 2f, backgroundTopY),
                size = Size(meterWidth+backgroundPad, backgroundHeight),
                cornerRadius = CornerRadius(30f, 30f)
            )

            drawLine(
                color = Color.Gray,
                start = Offset(meterStartX, centerY),
                end = Offset(meterEndX, centerY),
                strokeWidth = 4f
            )

            val centCount = 5
            val maxCentsDisplay = 50f
            for(i in 0..centCount){
                val fraction = i / (centCount-1).toFloat()
                val x = meterStartX + fraction * meterWidth
                val cents = -maxCentsDisplay + (fraction * (maxCentsDisplay * 2))

                drawLine(color = Color.Gray,
                    start = Offset(x, centerY - 20f),
                    end = Offset(x, centerY + 20f),
                    strokeWidth = 2f)

                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        "${cents.toInt()}",
                        x,
                        centerY + 60f,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.BLACK
                            textAlign = android.graphics.Paint.Align.CENTER
                            textSize = 30f
                        }
                    )
                }
            }
            val normalizedPosition = (centsDifference.coerceIn(-maxCentsDisplay, maxCentsDisplay) / maxCentsDisplay)
            val indicatorX = meterStartX + meterWidth / 2f + (normalizedPosition * meterWidth / 2f)


            if (frequency > 0 && targetFrequency != null) {
                drawImage(
                    image = image,
                    topLeft = Offset(indicatorX - image.width / 2f, centerY - image.height / 2f)
                )

            }
        }
    }
}




class MainActivity : AppCompatActivity() {

    private lateinit var freqView: TextView
    private lateinit var noteView: TextView
    private lateinit var composeView: ComposeView

    private lateinit var dropdown: ComposeView

    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 100L
    private var audioRecordBufferSizeInShorts: Int = 0
    private val noteToFrequency: Map<String, Double> = guitarNotes.entries.associate { (freq, note) ->
        note to freq
    }

    private lateinit var customTuning: Map<Double, String>
    private lateinit var allTunings: Map<String, Map<Double, String>>


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        freqView = findViewById(R.id.freqView)
        noteView = findViewById(R.id.note_view)
        composeView = findViewById(R.id.compose_view)
        dropdown = findViewById(R.id.dropdown)
        var selectedTuning by mutableStateOf("Standard")

        val buttonToSecondActivity: Button = findViewById(R.id.button_to_second_activity)

        customTuning = loadCustomTuning(this)!!

        TuningState.initialize(applicationContext)

        allTunings = mapOf("Standard" to standardTuning, "Drop D" to dropD, "Open G" to standardTuning, "DADGAD" to standardTuning, "Custom" to customTuning)


        buttonToSecondActivity.setOnClickListener {
            val intent = Intent(this, TuningSet::class.java)
            startActivity(intent)
        }

        composeView.setContent {
            GuitarStringVisualizer(note = "N/A", frequency = 0f)
        }
        dropdown.setContent {androidx.compose.material3.MaterialTheme{
            TuningSelect(selectedTuning = selectedTuning, onTuningSelected = { selectedTuning = it }, tuningsMap = allTunings)
        }

        }

        startMicListeningWithAudioRecord(this)

    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMicListeningWithAudioRecord(context: MainActivity) {
        if (isAudioRecording) {
            Log.w("AudioRecordListener", "AudioRecord is already recording.")
            return
        }

        audioRecordBufferSizeInBytes = 4096 // tai 8192

        audioRecordBuffer = ShortArray(audioRecordBufferSizeInBytes / 2) // Each Short is 2 bytes for PCM_16BIT

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
                        delay(40)
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

