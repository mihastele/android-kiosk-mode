package com.example.kioskmodeapplication

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.kioskapp.MyDeviceAdminReceiver

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KIOSK_PIN = "1234" // Change this to your desired PIN
        private const val PREF_NAME = "KioskPrefs"
        private const val KEY_SELECTED_APP = "selected_app"
    }

    private lateinit var mAdminComponentName: ComponentName
    private lateinit var mDevicePolicyManager: DevicePolicyManager
    private lateinit var selectAppButton: Button
    private lateinit var exitKioskButton: Button
    private var selectedAppPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize device policy manager and admin component
        mAdminComponentName = MyDeviceAdminReceiver.getComponentName(this)
        mDevicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        // Initialize UI components
        selectAppButton = findViewById(R.id.selectAppButton)
        exitKioskButton = findViewById(R.id.exitKioskButton)
        
        // Set up buttons
        selectAppButton.setOnClickListener {
            showAppSelectionDialog()
        }
        
        exitKioskButton.setOnClickListener {
            showPinDialog()
        }
        
        // Load selected app if any
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        selectedAppPackage = prefs.getString(KEY_SELECTED_APP, null)
        updateUI()

        // Check if app is device owner and set kiosk policies
        if (mDevicePolicyManager.isDeviceOwnerApp(packageName)) {
            setKioskPolicies(true)
        } else {
            Toast.makeText(this, "Not a device owner, kiosk mode limited", Toast.LENGTH_LONG).show()
            // Try to use screen pinning as a fallback
            startLockTask()
        }

        // Start kiosk service to prevent app from being closed
        startService(Intent(this, KioskService::class.java))
    }

    override fun onStart() {
        super.onStart()
        // Hide system UI
        hideSystemUI()
    }

    override fun onResume() {
        super.onResume()
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        // Start service to bring back our activity if another app comes to foreground
        startService(Intent(this, KioskService::class.java))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Prevent using hardware buttons
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_HOME
                || super.onKeyDown(keyCode, event)
    }

    private fun hideSystemUI() {
        val decorView = window.decorView
        decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun setKioskPolicies(enable: Boolean) {
        if (enable) {
            // Set as lock task package
            mDevicePolicyManager.setLockTaskPackages(mAdminComponentName, arrayOf(packageName))

            // Set as home app
            val intentFilter = android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            mDevicePolicyManager.addPersistentPreferredActivity(
                mAdminComponentName, intentFilter,
                ComponentName(packageName, MainActivity::class.java.name))

            // Disable keyguard
            mDevicePolicyManager.setKeyguardDisabled(mAdminComponentName, true)

            // Start lock task mode
            startLockTask()
        } else {
            // Clear policies
            mDevicePolicyManager.clearPackagePersistentPreferredActivities(
                mAdminComponentName, packageName)
            mDevicePolicyManager.setKeyguardDisabled(mAdminComponentName, false)

            // Stop lock task mode
            stopLockTask()
        }
    }

    private fun showPinDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter PIN to Exit")

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        builder.setView(input)

        builder.setPositiveButton("OK") { _, _ ->
            val enteredPin = input.text.toString()
            if (enteredPin == KIOSK_PIN) {
                exitKioskMode()
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    private fun showAppSelectionDialog() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        val apps = pm.queryIntentActivities(intent, 0)
        val appNames = ArrayList<String>()
        val appPackages = ArrayList<String>()
        
        for (ri in apps) {
            val appName = ri.loadLabel(pm).toString()
            val packageName = ri.activityInfo.packageName
            if (packageName != this.packageName) { // Don't show our own app
                appNames.add(appName)
                appPackages.add(packageName)
            }
        }
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select Application to Lock")
        
        builder.setSingleChoiceItems(appNames.toTypedArray(), -1) { dialog, which ->
            selectedAppPackage = appPackages[which]
            // Save selection
            getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_SELECTED_APP, selectedAppPackage)
                .apply()
            
            updateUI()
            dialog.dismiss()
            
            // Start the selected app
            startSelectedApp()
        }
        
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
    
    private fun startSelectedApp() {
        selectedAppPackage?.let { pkg ->
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    
                    // Start kiosk service to monitor the app
                    val serviceIntent = Intent(this, KioskService::class.java).apply {
                        putExtra("TARGET_PACKAGE", pkg)
                    }
                    startService(serviceIntent)
                } else {
                    Toast.makeText(this, "Could not launch app", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error launching app: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateUI() {
        val textView = findViewById<android.widget.TextView>(R.id.textView)
        if (selectedAppPackage != null) {
            try {
                val appInfo = packageManager.getApplicationInfo(selectedAppPackage!!, 0)
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                textView.text = "Kiosk Mode: $appName"
            } catch (e: Exception) {
                textView.text = "Kiosk Mode: Unknown App"
            }
        } else {
            textView.text = "Select an app to lock"
        }
    }
    
    private fun exitKioskMode() {
        if (mDevicePolicyManager.isDeviceOwnerApp(packageName)) {
            setKioskPolicies(false)
        } else {
            stopLockTask()
        }
        stopService(Intent(this, KioskService::class.java))
        Toast.makeText(this, "Exiting kiosk mode", Toast.LENGTH_SHORT).show()
        finish()
    }
}