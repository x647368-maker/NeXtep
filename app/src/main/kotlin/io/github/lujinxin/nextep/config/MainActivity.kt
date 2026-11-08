package io.github.x647368maker.atomicworkbench.config

import android.graphics.Color
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import io.github.x647368maker.atomicworkbench.icons.LauncherIconManager

class MainActivity : AppCompatActivity() {
    private lateinit var settings: StageSettingsScreen
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        settings = StageSettingsScreen(this, SettingsRepository(this))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !settings.dark
            isAppearanceLightNavigationBars = !settings.dark
        }
        val root = settings.view
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(left = safe.left, top = safe.top, right = safe.right, bottom = maxOf(safe.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        settings.open(savedInstanceState?.getString("stage_page") ?: intent.getStringExtra("stage_page") ?: "home")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!settings.back()) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        ViewCompat.requestApplyInsets(root)
    }
    override fun onResume() {
        super.onResume()
        LauncherIconManager.onAppOpened(this)
        if (::settings.isInitialized) settings.refresh()
        UpdateCoordinator.maybeCheck(this) { if (::settings.isInitialized) settings.open(settings.page) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("stage_page", settings.page)
        super.onSaveInstanceState(outState)
    }
}
