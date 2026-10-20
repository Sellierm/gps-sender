package com.mathieu.gpssender

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class LogsActivity : AppCompatActivity(), AppLogger.LogListener {

    private lateinit var backButton: ImageButton
    private lateinit var copyButton: Button
    private lateinit var clearButton: Button
    private lateinit var autoScrollSwitch: MaterialSwitch
    private lateinit var logScrollView: ScrollView
    private lateinit var logsTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        backButton = findViewById(R.id.backButton)
        copyButton = findViewById(R.id.copyButton)
        clearButton = findViewById(R.id.clearButton)
        autoScrollSwitch = findViewById(R.id.autoScrollSwitch)
        logScrollView = findViewById(R.id.logScrollView)
        logsTextView = findViewById(R.id.logsTextView)

        backButton.setOnClickListener { finish() }

        copyButton.setOnClickListener {
            val logsText = logsTextView.text.toString()
            if (logsText.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("GPS Sender Logs", logsText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Logs copiés dans le presse-papier", Toast.LENGTH_SHORT).show()
            }
        }

        clearButton.setOnClickListener {
            AppLogger.clear()
        }

        loadInitialLogs()
    }

    override fun onResume() {
        super.onResume()
        AppLogger.setListener(this)
        loadInitialLogs()
    }

    override fun onPause() {
        super.onPause()
        AppLogger.setListener(null)
    }

    private fun loadInitialLogs() {
        logsTextView.text = AppPreferences.getDeviceId(this).let {
            AppLogger.getFormattedLogs()
        }
        scrollToBottom()
    }

    override fun onLogAdded(logLine: String) {
        runOnUiThread {
            logsTextView.append("$logLine\n")
            if (autoScrollSwitch.isChecked) {
                scrollToBottom()
            }
        }
    }

    override fun onLogsCleared() {
        runOnUiThread {
            logsTextView.text = ""
        }
    }

    private fun scrollToBottom() {
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
