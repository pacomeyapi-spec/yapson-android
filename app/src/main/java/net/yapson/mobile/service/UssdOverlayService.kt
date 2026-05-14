package net.yapson.mobile.service

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import net.yapson.mobile.R

/**
 * Service qui affiche un overlay par-dessus le menu USSD.
 * Montre la valeur à saisir à chaque étape et permet de la copier.
 */
class UssdOverlayService : Service() {

    companion object {
        private const val TAG = "UssdOverlay"
        const val ACTION_SHOW = "SHOW"
        const val ACTION_HIDE = "HIDE"
        const val EXTRA_STEPS = "steps"
        const val EXTRA_STEP_INDEX = "stepIndex"

        var onStepConfirmed: ((Int) -> Unit)? = null
    }

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var steps: ArrayList<String> = arrayListOf()
    private var currentIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hideOverlay()
                stopSelf()
            }
            ACTION_SHOW -> {
                @Suppress("UNCHECKED_CAST")
                steps = intent.getStringArrayListExtra(EXTRA_STEPS) ?: arrayListOf()
                currentIndex = intent.getIntExtra(EXTRA_STEP_INDEX, 0)
                showOverlay()
            }
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        hideOverlay()

        if (currentIndex >= steps.size) {
            stopSelf()
            return
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_ussd_guide, null)

        val step = steps[currentIndex]
        val stepNum = currentIndex + 2 // +2 car étape 1 déjà faite

        view.findViewById<TextView>(R.id.tvProgress).text =
            "Étape $stepNum/${steps.size + 1}"

        view.findViewById<TextView>(R.id.tvValue).text = step

        // Bouton copier
        view.findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ussd", step))
            Toast.makeText(this, "\"$step\" copié!", Toast.LENGTH_SHORT).show()
        }

        // Bouton étape suivante
        view.findViewById<Button>(R.id.btnNext).setOnClickListener {
            onStepConfirmed?.invoke(currentIndex)
            currentIndex++
            if (currentIndex < steps.size) {
                showOverlay() // Afficher l'étape suivante
            } else {
                hideOverlay()
                stopSelf()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        try {
            windowManager?.addView(view, params)
            overlayView = view
            Log.d(TAG, "Overlay affiché: étape $stepNum = '$step'")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur overlay: ${e.message}")
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            overlayView = null
        }
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }
}
