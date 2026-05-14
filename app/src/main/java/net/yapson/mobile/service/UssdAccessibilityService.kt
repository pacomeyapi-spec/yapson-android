package net.yapson.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UssdAccService"

        var pendingSteps: List<String> = emptyList()
        var currentStepIndex: Int = 0
        var isActive: Boolean = false
        var isProcessingStep: Boolean = false

        var onStepDone: ((Int, String) -> Unit)? = null
        var onComplete: (() -> Unit)? = null
        var onError: ((String) -> Unit)? = null

        fun startSequence(
            steps: List<String>,
            onStep: (Int, String) -> Unit,
            onDone: () -> Unit,
            onErr: (String) -> Unit
        ) {
            pendingSteps = steps.drop(1)
            currentStepIndex = 0
            isActive = pendingSteps.isNotEmpty()
            isProcessingStep = false
            onStepDone = onStep
            onComplete = onDone
            onError = onErr
            Log.d(TAG, "Séquence démarrée: ${pendingSteps.size} étapes restantes")
        }

        fun reset() {
            pendingSteps = emptyList()
            currentStepIndex = 0
            isActive = false
            isProcessingStep = false
            onStepDone = null
            onComplete = null
            onError = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            tryProcessNextStep()
            // Continuer le polling toutes les 1.5 secondes
            handler.postDelayed(this, 1500)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            // Écouter TOUS les événements de toutes les apps
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
        }
        Log.d(TAG, "✅ Service accessibilité connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isActive || isProcessingStep) return
        // Déclencher sur tout changement de fenêtre ou contenu
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            tryProcessNextStep()
        }
    }

    /**
     * Cherche activement un EditText dans toutes les fenêtres actives
     * et saisit l'étape courante si trouvé.
     */
    private fun tryProcessNextStep() {
        if (!isActive || isProcessingStep) return
        if (currentStepIndex >= pendingSteps.size) return

        // Chercher EditText dans la fenêtre active
        val root = rootInActiveWindow ?: return
        val editField = findEditText(root)
        root.recycle()

        if (editField == null) {
            Log.v(TAG, "Pas de EditText trouvé — attente...")
            return
        }

        isProcessingStep = true
        val step = pendingSteps[currentStepIndex]
        val stepNum = currentStepIndex + 2
        Log.d(TAG, "✏️ Saisie étape $stepNum: '$step'")
        onStepDone?.invoke(stepNum, step)

        // Saisir la valeur
        handler.post {
            val r = rootInActiveWindow
            val e = r?.let { findEditText(it) }
            if (e != null) {
                e.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle()
                args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step)
                e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "✅ Valeur saisie: '$step'")

                // Cliquer Envoyer après 600ms
                handler.postDelayed({
                    val r2 = rootInActiveWindow
                    if (r2 != null) {
                        val sent = clickSendButton(r2)
                        Log.d(TAG, "Bouton Envoyer: $sent")
                        r2.recycle()
                    }
                    currentStepIndex++
                    isProcessingStep = false

                    if (currentStepIndex >= pendingSteps.size) {
                        Log.d(TAG, "🎉 Toutes les étapes terminées!")
                        isActive = false
                        stopPolling()
                        onComplete?.invoke()
                        reset()
                    }
                    e.recycle()
                    r?.recycle()
                }, 600)
            } else {
                Log.w(TAG, "EditText perdu")
                isProcessingStep = false
                r?.recycle()
            }
        }
    }

    fun startPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, 2000) // Démarrer après 2s
    }

    fun stopPolling() {
        handler.removeCallbacks(pollRunnable)
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) { child.recycle(); return found }
            child.recycle()
        }
        return null
    }

    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        val labels = listOf("envoyer", "send", "ok", "valider", "confirm", "suivant", "next")
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (node.isClickable && (
                node.className?.contains("Button") == true ||
                labels.any { text.contains(it) || desc.contains(it) }
            )) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = search(child)
                if (found != null) { child.recycle(); return found }
                child.recycle()
            }
            return null
        }
        val btn = search(root) ?: return false
        btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        btn.recycle()
        return true
    }

    override fun onInterrupt() { isProcessingStep = false }
    override fun onDestroy() { super.onDestroy(); stopPolling(); reset() }
}
