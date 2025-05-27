package com.example.guitartuner


import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
//AUDIO PAGE
class SecondActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)

        val buttonToMainActivity: Button = findViewById(R.id.button_to_main_activity)

        buttonToMainActivity.setOnClickListener {

            val intent = Intent(this, MainActivity::class.java)

            startActivity(intent)
        }
    }
}