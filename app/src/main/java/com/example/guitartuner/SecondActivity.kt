package com.example.guitartuner


import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SecondActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second) // Make sure you have activity_second.xml

        val buttonToMainActivity: Button = findViewById(R.id.button_to_main_activity)

        buttonToMainActivity.setOnClickListener {
            // Option 1: Simply finish this activity to go back to the previous one in the stack
            // finish()

            // Option 2: Explicitly start MainActivity
            // This is useful if you want to ensure MainActivity is a fresh instance
            // or if you've modified the back stack in complex ways.
            // For simple back navigation, finish() is usually preferred.
            val intent = Intent(this, MainActivity::class.java)
            // Optional flags:
            // intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
        }
    }
}