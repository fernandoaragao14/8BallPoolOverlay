package app.hack.eightballpool

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.hack.eightballpool.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"
        const val GAME_PACKAGE = "com.miniclip.eightballpool"
        const val ACTION_AUTOMATION_START_OVERLAY =
            "app.hack.eightballpool.action.AUTOMATION_START_OVERLAY"
    }

    private lateinit var binding: ActivityMainBinding
    private var automationHandled = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) R.string.toast_notification_granted else R.string.toast_notification_denied,
                Toast.LENGTH_LONG
            ).show()
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        bindActions()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        maybeRunAutomationAction()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        automationHandled = false
    }

    private fun bindActions() {
        binding.openAppInfoButton.setOnClickListener { openAppInfoSettings() }
        binding.openOverlaySettingsButton.setOnClickListener { openOverlaySettings() }
        binding.openNotificationPermissionButton.setOnClickListener { requestNotificationPermission() }
        binding.openPlayStoreButton.setOnClickListener { launchPlayStore() }
        binding.launchOverlayButton.setOnClickListener { launchOverlayAndGame() }
        binding.stopOverlayButton.setOnClickListener { stopOverlayService() }
    }

    private fun maybeRunAutomationAction() {
        if (automationHandled || !isDebugBuild()) {
            return
        }

        if (intent?.action == ACTION_AUTOMATION_START_OVERLAY) {
            automationHandled = true
            launchOverlayAndGame()
        }
    }

    private fun isDebugBuild(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun refreshUi() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val notificationsGranted = areNotificationsEnabled()
        val gameInstalled = isGameInstalled()

        binding.overlayStatusValue.text =
            getString(if (overlayGranted) R.string.status_granted else R.string.status_required)
        binding.notificationStatusValue.text =
            getString(
                if (notificationsGranted) {
                    R.string.status_granted
                } else {
                    R.string.status_optional
                }
            )
        binding.gameStatusValue.text =
            getString(if (gameInstalled) R.string.status_installed else R.string.status_missing)

        val grantedColor = ContextCompat.getColor(this, R.color.accentGreen)
        val pendingColor = ContextCompat.getColor(this, R.color.accentAmber)

        binding.overlayStatusValue.setTextColor(if (overlayGranted) grantedColor else pendingColor)
        binding.notificationStatusValue.setTextColor(if (notificationsGranted) grantedColor else pendingColor)
        binding.gameStatusValue.setTextColor(if (gameInstalled) grantedColor else pendingColor)

        binding.openOverlaySettingsButton.isEnabled = !overlayGranted
        binding.openNotificationPermissionButton.isEnabled = !notificationsGranted
        binding.openNotificationPermissionButton.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                View.VISIBLE
            } else {
                View.GONE
            }
        binding.openPlayStoreButton.visibility =
            if (gameInstalled) View.GONE else View.VISIBLE
        binding.launchOverlayButton.isEnabled = overlayGranted
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (areNotificationsEnabled()) {
            refreshUi()
            return
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launchOverlayAndGame() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_overlay_required, Toast.LENGTH_LONG).show()
            refreshUi()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !areNotificationsEnabled()) {
            requestNotificationPermission()
        }

        val gameIntent = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
        if (gameIntent == null) {
            launchPlayStore()
            return
        }

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_SERVICE
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        gameIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(gameIntent) }
            .onFailure {
                Log.e(TAG, "Unable to open game", it)
                Toast.makeText(this, R.string.toast_open_game_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun stopOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_SERVICE
        }
        startService(serviceIntent)
        Toast.makeText(this, R.string.toast_stop_overlay, Toast.LENGTH_SHORT).show()
    }

    private fun openOverlaySettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        } else {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        }

        runCatching { startActivity(intent) }
            .onFailure {
                Log.e(TAG, "Unable to open overlay settings", it)
                Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun openAppInfoSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }

        runCatching { startActivity(intent) }
            .onFailure {
                Log.e(TAG, "Unable to open app info", it)
                Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun launchPlayStore() {
        val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://play.google.com/store/apps/details?id=$GAME_PACKAGE")
            setPackage("com.android.vending")
        }

        runCatching { startActivity(playStoreIntent) }
            .recoverCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GAME_PACKAGE"))
                )
            }
            .onFailure {
                Log.e(TAG, "Unable to open Play Store", it)
                Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun isGameInstalled(): Boolean =
        packageManager.getLaunchIntentForPackage(GAME_PACKAGE) != null

    private fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun applyWindowInsets() {
        val contentPaddingBottom = binding.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            binding.content.updatePadding(bottom = contentPaddingBottom + systemBars.bottom)
            insets
        }
    }
}
