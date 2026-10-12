package com.mudassir.vidstash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.mudassir.vidstash.databinding.ActivityWelcomeBinding
import com.mudassir.vidstash.databinding.ItemWelcomeFeatureBinding

/** Shown once, on first start: what the app does and a responsible-use note. */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityWelcomeBinding

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateNotifyRow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        listOf(
            Triple(R.drawable.ic_download, R.string.welcome_f1_title, R.string.welcome_f1_text),
            Triple(R.drawable.ic_playlist, R.string.welcome_f2_title, R.string.welcome_f2_text),
            Triple(R.drawable.ic_play, R.string.welcome_f3_title, R.string.welcome_f3_text),
            Triple(R.drawable.ic_check, R.string.welcome_f4_title, R.string.welcome_f4_text),
        ).forEach { (icon, title, text) ->
            val row = ItemWelcomeFeatureBinding.inflate(layoutInflater, b.features, true)
            row.icon.setImageResource(icon)
            row.title.setText(title)
            row.text.setText(text)
        }

        b.notifyButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        updateNotifyRow()

        b.startButton.setOnClickListener {
            Settings(this).welcomed = true
            finish()
        }
    }

    private fun updateNotifyRow() {
        val needed = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        b.notifyText.isVisible = needed
        b.notifyButton.isVisible = needed
    }

    @Deprecated("Back finishes the welcome too")
    override fun onBackPressed() {
        Settings(this).welcomed = true
        super.onBackPressed()
    }
}
