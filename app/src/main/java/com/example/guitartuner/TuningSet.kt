package com.example.guitartuner

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity

class TuningSet : AppCompatActivity() {

    private lateinit var btnSaveTuning: Button

    private lateinit var btnBack : Button

    companion object {
        const val PREFS_NAME = "GuitarTunerPrefs"
        const val KEY_STRING_1 = "string1_tuning"
        const val KEY_STRING_2 = "string2_tuning"
        const val KEY_STRING_3 = "string3_tuning"
        const val KEY_STRING_4 = "string4_tuning"
        const val KEY_STRING_5 = "string5_tuning"
        const val KEY_STRING_6 = "string6_tuning"
    }

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var spinners: List<Spinner>

    private val guitarNotes2 = mapOf(
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.custom_tuning)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val spinners = listOf<Spinner>(
            findViewById(R.id.string1),
            findViewById(R.id.string2),
            findViewById(R.id.string3),
            findViewById(R.id.string4),
            findViewById(R.id.string5),
            findViewById(R.id.string6)
        )

        val notes = guitarNotes2.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, notes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinners.forEach { it.adapter = adapter }

        // Load saved tuning preferences
        val savedTuning = arrayOfNulls<String>(6)
        savedTuning[0] = sharedPreferences.getString(KEY_STRING_1, notes.firstOrNull())
        savedTuning[1] = sharedPreferences.getString(KEY_STRING_2, notes.firstOrNull())
        savedTuning[2] = sharedPreferences.getString(KEY_STRING_3, notes.firstOrNull())
        savedTuning[3] = sharedPreferences.getString(KEY_STRING_4, notes.firstOrNull())
        savedTuning[4] = sharedPreferences.getString(KEY_STRING_5, notes.firstOrNull())
        savedTuning[5] = sharedPreferences.getString(KEY_STRING_6, notes.firstOrNull())

        for (i in spinners.indices) {
            val spinner = spinners[i]
            val savedNote = savedTuning[i]
            if (savedNote != null) {
                val position = notes.indexOf(savedNote)
                if (position >= 0) {
                    spinner.setSelection(position)
                }
            }
        }


        btnSaveTuning = findViewById(R.id.btnSave)
        btnBack = findViewById(R.id.btnBack)

        //loadTuning()

        btnSaveTuning.setOnClickListener {
            saveTuning(spinners)
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun saveTuning(spinners: List<Spinner>) {
        val editor = sharedPreferences.edit()
        editor.putString(KEY_STRING_1, spinners[0].selectedItem.toString())
        editor.putString(KEY_STRING_2, spinners[1].selectedItem.toString())
        editor.putString(KEY_STRING_3, spinners[2].selectedItem.toString())
        editor.putString(KEY_STRING_4, spinners[3].selectedItem.toString())
        editor.putString(KEY_STRING_5, spinners[4].selectedItem.toString())
        editor.putString(KEY_STRING_6, spinners[5].selectedItem.toString())
        editor.apply()
        Toast.makeText(this, "Tuning saved!", Toast.LENGTH_SHORT).show()
    }


}