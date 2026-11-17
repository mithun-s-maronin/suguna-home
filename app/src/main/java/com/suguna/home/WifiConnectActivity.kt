package com.suguna.home

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * In-app Wi-Fi panel — recreated from the original Sure MDM WifiConnectActivity.
 * Same layout, same list, same password panel, same signal bars and labels.
 *
 * The original was Device Owner and connected with WifiManager.addNetwork(). A normal app
 * can't do that on Android 10+, so connect uses the system "Add network" flow instead; the
 * on-screen panel is unchanged.
 */
class WifiConnectActivity : Activity() {

    private data class WifiNetwork(
        val ssid: String,
        val level: Int,
        val secured: Boolean,
        val connected: Boolean,
    )

    private lateinit var wifiManager: WifiManager
    private lateinit var rvNetworks: ListView
    private lateinit var progressScan: ProgressBar
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var panelPassword: LinearLayout
    private lateinit var tvPanelSsid: TextView
    private lateinit var etPassword: EditText
    private lateinit var btnTogglePassword: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val networks = mutableListOf<WifiNetwork>()
    private val adapter = NetworkAdapter()
    private var selectedSsid: String? = null
    private var passwordVisible = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) populateNetworks()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        setContentView(R.layout.activity_wifi_connect)
        // Black bottom navigation bar to match the dark Wi-Fi screen.
        window.navigationBarColor = Color.BLACK

        rvNetworks = findViewById(R.id.rvNetworks)
        progressScan = findViewById(R.id.progressScan)
        tvHint = findViewById(R.id.tvHint)
        tvStatus = findViewById(R.id.tvStatus)
        panelPassword = findViewById(R.id.panelPassword)
        tvPanelSsid = findViewById(R.id.tvPanelSsid)
        etPassword = findViewById(R.id.etPassword)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)

        rvNetworks.adapter = adapter
        rvNetworks.setOnItemClickListener { _, _, position, _ -> onNetworkTapped(networks[position]) }
        rvNetworks.setOnItemLongClickListener { _, _, position, _ ->
            onNetworkLongTapped(networks[position]); true
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            if (panelPassword.visibility == View.VISIBLE) hidePasswordPanel() else finish()
        }
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { startScan() }
        findViewById<TextView>(R.id.btnCancelConnect).setOnClickListener { hidePasswordPanel() }
        findViewById<TextView>(R.id.btnConnect).setOnClickListener { onConnectClicked() }
        btnTogglePassword.setOnClickListener { togglePasswordVisibility() }
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onConnectClicked(); true
            } else {
                false
            }
        }

        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_LOCATION,
            )
        } else {
            startScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) startScan()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(scanReceiver)
        } catch (e: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panelPassword.visibility == View.VISIBLE) hidePasswordPanel() else super.onBackPressed()
    }

    // ── Scan ───────────────────────────────────────────────────────────

    private fun startScan() {
        hidePasswordPanel()
        progressScan.visibility = View.VISIBLE
        tvHint.text = "Scanning for networks..."
        tvHint.visibility = View.VISIBLE

        if (!wifiManager.isWifiEnabled) {
            // Android 10+ can't flip Wi-Fi silently; send the user to the Wi-Fi settings panel.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivity(Intent(Settings.Panel.ACTION_WIFI))
            } else {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            }
        }
        if (!hasLocationPermission()) {
            progressScan.visibility = View.GONE
            tvHint.text = "Location permission required to scan Wi-Fi"
            return
        }
        if (!isLocationServiceOn()) {
            progressScan.visibility = View.GONE
            tvHint.text = "Turn on Location to scan Wi-Fi"
            return
        }
        @Suppress("DEPRECATION")
        wifiManager.startScan()
        populateNetworks()
    }

    @Suppress("DEPRECATION")
    private fun populateNetworks() {
        progressScan.visibility = View.GONE
        if (!hasLocationPermission()) return
        val currentSsid = currentSsid()
        val results = try {
            wifiManager.scanResults
        } catch (e: SecurityException) {
            emptyList<ScanResult>()
        }
        val mapped = results
            .filter { !it.SSID.isNullOrBlank() }
            .distinctBy { it.SSID }
            .sortedWith(compareByDescending<ScanResult> { it.SSID == currentSsid }.thenByDescending { it.level })
            .map {
                WifiNetwork(
                    ssid = it.SSID,
                    level = WifiManager.calculateSignalLevel(it.level, 4),
                    secured = isSecured(it),
                    connected = it.SSID == currentSsid,
                )
            }
        networks.clear()
        networks.addAll(mapped)
        adapter.notifyDataSetChanged()
        tvHint.visibility = if (networks.isEmpty()) View.VISIBLE else View.GONE
        if (networks.isEmpty()) tvHint.text = "No networks found. Tap ↻ to scan again."
    }

    // ── Taps ───────────────────────────────────────────────────────────

    private fun onNetworkTapped(network: WifiNetwork) {
        when {
            network.connected -> showStatus("Already connected to ${network.ssid}", "#4CAF50")
            network.secured -> showPasswordPanel(network.ssid)
            else -> initiateConnect(network.ssid, null)
        }
    }

    private fun onNetworkLongTapped(network: WifiNetwork) {
        AlertDialog.Builder(this)
            .setTitle("Forget network?")
            .setMessage("\"${network.ssid}\" will be removed from saved networks.")
            .setPositiveButton("Forget") { _, _ -> forgetNetwork(network.ssid) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun forgetNetwork(ssid: String) {
        // A normal app can only forget networks it added itself; otherwise send to Wi-Fi settings.
        val configs = try {
            wifiManager.configuredNetworks
        } catch (e: Exception) {
            null
        }
        val match = configs?.firstOrNull { it.SSID == "\"$ssid\"" }
        if (match != null && wifiManager.removeNetwork(match.networkId)) {
            wifiManager.saveConfiguration()
            showStatus("Forgot $ssid", "#4CAF50")
            startScan()
        } else {
            showStatus("Open Wi-Fi settings to forget $ssid", "#FFC107")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startActivity(Intent(Settings.Panel.ACTION_WIFI))
        }
    }

    // ── Password panel ─────────────────────────────────────────────────

    private fun showPasswordPanel(ssid: String) {
        selectedSsid = ssid
        tvPanelSsid.text = ssid
        etPassword.setText("")
        passwordVisible = false
        etPassword.inputType = 129 // text|password
        btnTogglePassword.text = "👁"
        panelPassword.visibility = View.VISIBLE
        etPassword.requestFocus()
        showKeyboard()
    }

    private fun hidePasswordPanel() {
        panelPassword.visibility = View.GONE
        hideKeyboard()
    }

    private fun togglePasswordVisibility() {
        passwordVisible = !passwordVisible
        val pos = etPassword.selectionEnd
        if (passwordVisible) {
            etPassword.inputType = 145 // text|visiblePassword
            btnTogglePassword.text = "🙈"
        } else {
            etPassword.inputType = 129
            btnTogglePassword.text = "👁"
        }
        etPassword.setSelection(pos.coerceAtLeast(0))
    }

    private fun onConnectClicked() {
        val ssid = selectedSsid ?: return
        val password = etPassword.text.toString().trim()
        if (password.isEmpty()) {
            etPassword.error = "Enter password"
            return
        }
        hidePasswordPanel()
        initiateConnect(ssid, password)
    }

    // ── Connect ────────────────────────────────────────────────────────

    private fun initiateConnect(ssid: String, password: String?) {
        showStatus("Connecting to $ssid…", "#FFC107")
        // As Device Owner (or pre-Android-10), join directly with WifiManager — this actually
        // associates. The system "Add network" dialog only *saves* the network and often does not
        // connect, which is what produced the false "wrong password" message.
        val canLegacy = Kiosk.isDeviceOwner(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (canLegacy && connectLegacy(ssid, password)) {
            pollStatus(ssid, 0)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            connectViaSuggestion(ssid, password)
        } else {
            showStatus("Could not connect to $ssid", "#F44336")
        }
    }

    /** Android 10+: hand the network to the system, which shows an "Add network?" confirmation. */
    private fun connectViaSuggestion(ssid: String, password: String?) {
        try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (!password.isNullOrEmpty()) builder.setWpa2Passphrase(password)
            val suggestion = builder.build()
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                putParcelableArrayListExtra(
                    Settings.EXTRA_WIFI_NETWORK_LIST,
                    arrayListOf(suggestion),
                )
            }
            startActivity(intent)
            pollStatus(ssid, 0)
        } catch (e: Exception) {
            Log.e(TAG, "connectViaSuggestion failed: ${e.message}")
            showStatus("Open Wi-Fi settings to connect to $ssid", "#F44336")
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(ssid: String, password: String?): Boolean {
        return try {
            if (!wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = true
                Thread.sleep(800L)
            }
            wifiManager.configuredNetworks
                ?.filter { it.SSID == "\"$ssid\"" }
                ?.forEach { wifiManager.removeNetwork(it.networkId) }
            val config = WifiConfiguration()
            config.SSID = "\"$ssid\""
            if (!password.isNullOrEmpty()) {
                config.preSharedKey = "\"$password\""
            } else {
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            val netId = wifiManager.addNetwork(config)
            if (netId == -1) return false
            wifiManager.disconnect()
            val enabled = wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()
            enabled
        } catch (e: Exception) {
            Log.e(TAG, "connectLegacy failed: ${e.message}")
            false
        }
    }

    private fun pollStatus(ssid: String, attempts: Int) {
        handler.postDelayed({
            if (currentSsid() == ssid) {
                showStatus("Connected to $ssid ✓", "#4CAF50")
                populateNetworks()
            } else if (attempts >= MAX_POLL_ATTEMPTS) {
                showStatus("Could not connect to $ssid — check the password or signal", "#F44336")
            } else {
                pollStatus(ssid, attempts + 1)
            }
        }, POLL_INTERVAL_MS)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun showStatus(message: String, colorHex: String) {
        val color = Color.parseColor(colorHex)
        tvStatus.text = message
        tvStatus.setTextColor(color)
        tvStatus.setBackgroundColor(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)))
        tvStatus.visibility = View.VISIBLE
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLocationServiceOn(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        lm.isLocationEnabled
    } catch (e: Exception) {
        true
    }

    private fun isSecured(r: ScanResult): Boolean {
        val caps = r.capabilities ?: return false
        return caps.contains("WEP") || caps.contains("WPA") || caps.contains("PSK") || caps.contains("EAP")
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? {
        val ssid = wifiManager.connectionInfo?.ssid?.trim('"') ?: return null
        return if (ssid.isNotEmpty() && ssid != "<unknown ssid>") ssid else null
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etPassword, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etPassword.windowToken, 0)
    }

    // ── List adapter ───────────────────────────────────────────────────

    private inner class NetworkAdapter : BaseAdapter() {
        override fun getCount(): Int = networks.size
        override fun getItem(position: Int): Any = networks[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_wifi_network, parent, false)
            val n = networks[position]
            view.findViewById<TextView>(R.id.tvSsid).text = n.ssid
            view.findViewById<TextView>(R.id.tvSignalIcon).text = bars(n.level)
            view.findViewById<TextView>(R.id.tvMeta).text =
                (if (n.secured) "Secured" else "Open") + " · " + signalLabel(n.level)
            view.findViewById<TextView>(R.id.tvConnected).visibility =
                if (n.connected) View.VISIBLE else View.GONE
            return view
        }

        private fun bars(level: Int): String = when (level) {
            1 -> "▂···"
            2 -> "▂▄··"
            3 -> "▂▄▆·"
            4 -> "▂▄▆█"
            else -> "····"
        }

        private fun signalLabel(level: Int): String = when (level) {
            1 -> "Weak"
            2 -> "Fair"
            3 -> "Good"
            4 -> "Excellent"
            else -> "No signal"
        }
    }

    private companion object {
        const val TAG = "WifiConnectActivity"
        const val REQ_LOCATION = 71
        const val MAX_POLL_ATTEMPTS = 12
        const val POLL_INTERVAL_MS = 1500L
    }
}
