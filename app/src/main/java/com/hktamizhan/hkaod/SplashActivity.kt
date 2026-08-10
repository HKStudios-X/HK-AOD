package com.hktamizhan.hkaod
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("isFirstRun", true)
        if (!isFirstRun) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            intent.action != null && intent.action == Intent.ACTION_MAIN) {
            finish()
            return
        }

        setContentView(R.layout.activity_splash)


        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        }

        val videoView = findViewById<VideoView>(R.id.videoView)
        val videoPath = "android.resource://" + packageName + "/" + R.raw.intro
        val uri = Uri.parse(videoPath)

        videoView.setVideoURI(uri)
        videoView.start()
        videoView.setOnCompletionListener {
            prefs.edit().putBoolean("isFirstRun", false).apply()
            val intent = Intent(this@SplashActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}