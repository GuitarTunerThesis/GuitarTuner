package com.example.guitartuner


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts

//AUDIO PAGE


fun startRecording() {
    println("Permission granted")
}


class SecondActivity : AppCompatActivity() {

    private var recorder: MediaRecorder? = null
    private lateinit var micLevelBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 100L

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {

                startMicListening(this)
            } else {

                Log.e("MicListener", "RECORD_AUDIO permission denied")

            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)

        micLevelBar = findViewById(R.id.mic_level_bar)

        val buttonToMainActivity: Button = findViewById(R.id.button_to_main_activity)

        buttonToMainActivity.setOnClickListener {

            val intent = Intent(this, MainActivity::class.java)

            startActivity(intent)
        }

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startMicListening(this)
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {

                Log.i("MicListener", "Showing rationale for RECORD_AUDIO permission")
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        startMicListening(this)

    }

    private fun startMicListening(context: Context) {
        recorder?.release()
        recorder = null

        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context.applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
                recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile("${externalCacheDir?.absolutePath}/test.3gp")
                prepare()
                start()
                    Log.d("MicListener", "Starting microphone listening")
            }
            updateMicLevel()
        } catch (e: Exception) {
            Log.e("MicListener", "Failed to start microphone listeningAAAAAAA", e)
            recorder?.release()
            recorder = null
        }
    }


    private fun updateMicLevel() {
        recorder?.let {
            val maxAmplitude = it.maxAmplitude // Range: 0–32767
            val level = (maxAmplitude / 32767.0 * 100).toInt()
            micLevelBar.progress = level
        }
        handler.postDelayed({ updateMicLevel() }, updateInterval)
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder?.stop()
        recorder?.release()
        recorder = null
        handler.removeCallbacksAndMessages(null)
    }

}