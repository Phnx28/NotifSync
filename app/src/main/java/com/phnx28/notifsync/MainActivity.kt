package com.phnx28.notifsync

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.phnx28.notifsync.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v0.2.1 — permission requests moved to HomeFragment / SenderFragment /
        // PairingFragment so the user only sees prompts relevant to the mode
        // they picked (AUDIT.md L-01). Previously the SMS + location
        // permission dialog popped up on first launch before the user had
        // even chosen Sender or Receiver mode.
    }
}
