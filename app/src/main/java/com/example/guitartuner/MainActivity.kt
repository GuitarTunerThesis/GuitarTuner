package com.example.guitartuner

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Make sure you have this layout file

        val buttonToSecondActivity: Button = findViewById(R.id.button_to_second_activity) // Assuming your button has this ID in your layout

        buttonToSecondActivity.setOnClickListener {
            // Create an Intent to start SecondActivity
            val intent = Intent(this, SecondActivity::class.java)
            startActivity(intent)
        }
    }
}

//class SecondActivity {
//
//}
