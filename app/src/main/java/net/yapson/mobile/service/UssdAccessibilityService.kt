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
            pendingSteps = steps.drop(1) // Étape 1 déjà faite via Intent
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        Log.d(TAG, "✅ Service accessibilité connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isActive || event == null) return
        if (currentStepIndex >= pendingSteps.size) return
        if (isProcessingStep) return

        val pkg = event.packageName?.toString() ?: ""

        // Logger pour debug
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Fenêtre: pkg=$pkg")
        }

        // Accepter TOUS les packages — on vérifie seulement la présence d'un EditText
        // Le menu USSD Samsung peut apparaître dans com.android.phone, com.samsung.android.dialer, etc.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val rootNode = rootInActiveWindow ?: return
        val editField = findEditText(rootNode)
        rootNode.recycle()

        if (editField == null) return

        // Poser le verrou
        isProcessingStep = true

        val step = pendingSteps[currentStepIndex]
        val stepNum = currentStepIndex + 2
        Log.d(TAG, "Saisie étape $stepNum: '$step'")
        onStepDone?.invoke(stepNum, step)

        // Saisir la valeur
        editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        handler.postDelayed({
            val root2 = rootInActiveWindow
            val edit2 = root2?.let { findEditText(it) }

            if (edit2 != null) {
                // Vider puis saisir
                val args = Bundle()
                args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step)
                edit2.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                handler.postDelayed({
                    // Cliquer Envoyer
                    val root3 = rootInActiveWindow
                    if (root3 != null) {
                        val clicked = clickSendButton(root3)
                        Log.d(TAG, "Bouton Envoyer cliqué: $clicked")
                        root3.recycle()
                    }

                    currentStepIndex++
                    isProcessingStep = false

                    if (currentStepIndex >= pendingSteps.size) {
                        Log.d(TAG, "✅ Séquence terminée")
                        isActive = false
                        onComplete?.invoke()
                        reset()
                    }

                    edit2.recycle()
                    root2?.recycle()
                }, 800)
            } else {
                Log.w(TAG, "EditText perdu — retry")
                isProcessingStep = false
                root2?.recycle()
            }
        }, 400)

        editField.recycle()
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
        Log.d(TAG, "Cliqué: '${btn.text}'")
        btn.recycle()
        return true
    }

    override fun onInterrupt() {
        isProcessingStep = false
    }

    override fun onDestroy() {
        super.onDestroy()
        reset()
    }
}
