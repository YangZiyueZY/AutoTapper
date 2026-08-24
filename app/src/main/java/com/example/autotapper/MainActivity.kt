package com.example.autotapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var dotOverlay: View
    private lateinit var dotAccessibility: View
    private lateinit var tvOverlayState: TextView
    private lateinit var tvAccessibilityState: TextView
    private lateinit var rvPoints: RecyclerView
    private lateinit var tvPointsEmpty: TextView
    private lateinit var tvPointsCount: TextView
    private lateinit var tvConfigSummary: TextView
    private lateinit var etIntervalMs: EditText
    private lateinit var etRandomExtraMs: EditText
    private lateinit var etRepeatCount: EditText

    private var pointReceiverRegistered = false
    private var points: List<TapPoint> = emptyList()
    private lateinit var pointsAdapter: PointListAdapter

    private val pointReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AutoTapperConfig.ACTION_POINT_SELECTED) {
                return
            }
            loadAndShowPoints()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dotOverlay = findViewById(R.id.dot_overlay)
        dotAccessibility = findViewById(R.id.dot_accessibility)
        tvOverlayState = findViewById(R.id.tv_overlay_state)
        tvAccessibilityState = findViewById(R.id.tv_accessibility_state)
        rvPoints = findViewById(R.id.rv_points)
        tvPointsEmpty = findViewById(R.id.tv_points_empty)
        tvPointsCount = findViewById(R.id.tv_points_count)
        tvConfigSummary = findViewById(R.id.tv_config_summary)
        etIntervalMs = findViewById(R.id.et_interval_ms)
        etRandomExtraMs = findViewById(R.id.et_random_extra_ms)
        etRepeatCount = findViewById(R.id.et_repeat_count)

        pointsAdapter = PointListAdapter { index -> deletePoint(index) }
        rvPoints.layoutManager = LinearLayoutManager(this)
        rvPoints.adapter = pointsAdapter

        findViewById<View>(R.id.btn_grant_overlay).setOnClickListener {
            PermissionUtils.requestOverlayPermission(this)
        }
        findViewById<View>(R.id.btn_grant_accessibility).setOnClickListener {
            PermissionUtils.requestAccessibilityPermission(this)
        }
        findViewById<View>(R.id.btn_open_controller).setOnClickListener {
            openFloatingController()
        }
        findViewById<View>(R.id.btn_start).setOnClickListener {
            startAutoTap()
        }
        findViewById<View>(R.id.btn_stop).setOnClickListener {
            stopAutoTap()
        }

        etIntervalMs.doAfterTextChanged { updateConfigSummary() }
        etRandomExtraMs.doAfterTextChanged { updateConfigSummary() }
        etRepeatCount.doAfterTextChanged { updateConfigSummary() }

        loadSavedConfig()
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        registerPointReceiver()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onStop() {
        super.onStop()
        unregisterPointReceiver()
    }

    private fun loadSavedConfig() {
        val prefs = AutoTapperConfig.prefs(this)
        etIntervalMs.setText(prefs.getLong(AutoTapperConfig.KEY_INTERVAL_MS, 400L).toString())
        etRandomExtraMs.setText(
            prefs.getLong(AutoTapperConfig.KEY_RANDOM_EXTRA_MS, 80L).toString()
        )
        etRepeatCount.setText(prefs.getInt(AutoTapperConfig.KEY_REPEAT_COUNT, 0).toString())
    }

    private fun updateStatus() {
        updatePermissionRow(
            dotOverlay,
            tvOverlayState,
            R.string.overlay_permission_state,
            PermissionUtils.isOverlayPermissionGranted(this)
        )
        updatePermissionRow(
            dotAccessibility,
            tvAccessibilityState,
            R.string.accessibility_permission_state,
            PermissionUtils.isAccessibilityServiceEnabled(this, AutoClickService::class.java)
        )
        loadAndShowPoints()
    }

    private fun updatePermissionRow(dot: View, textView: TextView, templateRes: Int, granted: Boolean) {
        textView.text = getString(
            templateRes,
            getString(if (granted) R.string.permission_granted else R.string.permission_missing)
        )
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (granted) "#16A34A" else "#DC2626"))
        }
    }

    private fun loadAndShowPoints() {
        points = AutoTapperConfig.loadPoints(this)
        pointsAdapter.submitPoints(points)
        val empty = points.isEmpty()
        tvPointsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvPoints.visibility = if (empty) View.GONE else View.VISIBLE
        tvPointsCount.text = getString(R.string.points_count_badge, points.size)
        updateConfigSummary()
    }

    private fun deletePoint(index: Int) {
        val current = AutoTapperConfig.loadPoints(this)
        if (index !in current.indices) {
            return
        }
        AutoTapperConfig.removePointAt(this, index)
        loadAndShowPoints()
        Toast.makeText(this, getString(R.string.point_deleted_toast, index + 1), Toast.LENGTH_SHORT)
            .show()
    }

    private fun openFloatingController() {
        if (!PermissionUtils.isOverlayPermissionGranted(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
            PermissionUtils.requestOverlayPermission(this)
            return
        }

        val hasPoints = AutoTapperConfig.hasPoints(this)
        val intent = Intent(this, OverlayService::class.java).apply {
            action = AutoTapperConfig.ACTION_SHOW_CONTROLLER
            putExtra(AutoTapperConfig.EXTRA_START_PICKING, !hasPoints)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(
            this,
            if (hasPoints) R.string.overlay_started else R.string.overlay_started_pick,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startAutoTap() {
        if (!PermissionUtils.isAccessibilityServiceEnabled(this, AutoClickService::class.java)) {
            Toast.makeText(this, R.string.accessibility_permission_required, Toast.LENGTH_SHORT)
                .show()
            PermissionUtils.requestAccessibilityPermission(this)
            return
        }

        if (AutoTapperConfig.loadPoints(this).isEmpty()) {
            Toast.makeText(this, R.string.point_required, Toast.LENGTH_SHORT).show()
            return
        }

        val intervalMs = etIntervalMs.text.toString().toLongOrNull()?.coerceAtLeast(100L)
        val randomExtraMs = etRandomExtraMs.text.toString().toLongOrNull()?.coerceAtLeast(0L)
        val repeatCount = etRepeatCount.text.toString().toIntOrNull()?.coerceAtLeast(0)

        if (intervalMs == null || randomExtraMs == null || repeatCount == null) {
            Toast.makeText(this, R.string.invalid_number_input, Toast.LENGTH_SHORT).show()
            return
        }

        AutoTapperConfig.prefs(this).edit()
            .putLong(AutoTapperConfig.KEY_INTERVAL_MS, intervalMs)
            .putLong(AutoTapperConfig.KEY_RANDOM_EXTRA_MS, randomExtraMs)
            .putInt(AutoTapperConfig.KEY_REPEAT_COUNT, repeatCount)
            .apply()

        sendBroadcast(Intent(AutoTapperConfig.ACTION_START_CLICKING).setPackage(packageName))
        Toast.makeText(this, R.string.start_command_sent, Toast.LENGTH_SHORT).show()
    }

    private fun stopAutoTap() {
        sendBroadcast(Intent(AutoTapperConfig.ACTION_STOP_CLICKING).setPackage(packageName))
        Toast.makeText(this, R.string.stop_command_sent, Toast.LENGTH_SHORT).show()
    }

    private fun updateConfigSummary() {
        val intervalInput = etIntervalMs.text.toString().toLongOrNull()
        val randomExtraInput = etRandomExtraMs.text.toString().toLongOrNull()
        val repeatCountInput = etRepeatCount.text.toString().toIntOrNull()

        if (intervalInput == null || randomExtraInput == null || repeatCountInput == null) {
            tvConfigSummary.text = getString(R.string.config_summary_invalid)
            return
        }

        val intervalMs = intervalInput.coerceAtLeast(100L)
        val randomExtraMs = randomExtraInput.coerceAtLeast(0L)
        val repeatCount = repeatCountInput.coerceAtLeast(0)

        val repeatSummary = if (repeatCount == 0) {
            getString(R.string.config_summary_repeat_infinite)
        } else {
            getString(R.string.config_summary_repeat_finite, repeatCount)
        }

        val summaryText = getString(
            R.string.config_summary_template,
            points.size,
            intervalMs,
            intervalMs + randomExtraMs,
            repeatSummary
        )

        tvConfigSummary.text = if (intervalInput < 100L) {
            "$summaryText\n${getString(R.string.interval_adjusted_note)}"
        } else {
            summaryText
        }
    }

    private fun registerPointReceiver() {
        if (pointReceiverRegistered) {
            return
        }

        val filter = IntentFilter(AutoTapperConfig.ACTION_POINT_SELECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pointReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pointReceiver, filter)
        }
        pointReceiverRegistered = true
    }

    private fun unregisterPointReceiver() {
        if (!pointReceiverRegistered) {
            return
        }

        unregisterReceiver(pointReceiver)
        pointReceiverRegistered = false
    }
}
