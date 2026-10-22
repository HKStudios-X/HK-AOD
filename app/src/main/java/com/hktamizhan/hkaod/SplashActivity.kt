package com.hktamizhan.hkaod
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    // Guards against navigating twice (completion + error/fallback racing).
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("isFirstRun", true)
        if (!isFirstRun) {
            goToMain()
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

        // If the intro can't play (unsupported codec, corrupt file, ...) or
        // never finishes, the user must never be stuck on a black screen.
        // A hard fallback closes the splash no matter what happens.
        Handler(Looper.getMainLooper()).postDelayed({ goToMain() }, 30_000L)

        videoView.setOnCompletionListener { goToMain() }
        videoView.setOnErrorListener { _, _, _ ->
            goToMain()
            true
        }
        videoView.setVideoURI(uri)
        videoView.start()
    }

    private fun goToMain() {
        if (navigated) return
        navigated = true
        getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
            .edit().putBoolean("isFirstRun", false).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
