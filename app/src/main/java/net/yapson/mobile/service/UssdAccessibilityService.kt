package net.yapson.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service d'accessibilité USSD — saisit UNE étape à la fois.
 * Attend que la fenêtre USSD se raffraîchisse avant de saisir l'étape suivante.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UssdAccService"

        var pendingSteps: List<String> = emptyList()
        var currentStepIndex: Int = 0
        var isActive: Boolean = false
        var isProcessingStep: Boolean = false  // Verrou pour éviter les doublons

        var onStepDone: ((Int, String) -> Unit)? = null
        var onComplete: (() -> Unit)? = null
        var onError: ((String) -> Unit)? = null

        fun startSequence(
            steps: List<String>,
            onStep: (Int, String) -> Unit,
            onDone: () -> Unit,
            onErr: (String) -> Unit
        ) {
            // Étape 1 déjà lancée via Intent ACTION_CALL
            pendingSteps = steps.drop(1)
            currentStepIndex = 0
            isActive = pendingSteps.isNotEmpty()
            isProcessingStep = false
            onStepDone = onStep
            onComplete = onDone
            onError = onErr
            Log.d(TAG, "Séquence démarrée: ${pendingSteps.size} étapes restantes à saisir")
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
            notificationTimeout = 200
        }
        Log.d(TAG, "✅ Service accessibilité connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isActive || event == null) return
        if (currentStepIndex >= pendingSteps.size) return
        if (isProcessingStep) return  // Déjà en train de traiter une étape

        // Vérifier que c'est bien une fenêtre téléphone/USSD
        val pkg = event.packageName?.toString() ?: ""
        val isPhone = pkg.contains("phone", ignoreCase = true) ||
                      pkg.contains("dialer", ignoreCase = true) ||
                      pkg.contains("incallui", ignoreCase = true)
        if (!isPhone) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val rootNode = rootInActiveWindow ?: return

        // Chercher le champ de saisie USSD
        val editField = findEditText(rootNode)
        if (editField == null) {
            rootNode.recycle()
            return
        }

        // Poser le verrou : une seule étape à la fois
        isProcessingStep = true

        val step = pendingSteps[currentStepIndex]
        val stepNum = currentStepIndex + 2  // +2 car étape 1 déjà faite
        Log.d(TAG, "Saisie étape $stepNum: $step")
        onStepDone?.invoke(stepNum, step)

        // 1. Vider le champ
        editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clearArgs = Bundle()
        clearArgs.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

        // 2. Saisir la valeur de CETTE étape seulement
        handler.postDelayed({
            val currentRoot = rootInActiveWindow
            val currentEdit = currentRoot?.let { findEditText(it) }

            if (currentEdit != null) {
                val args = Bundle()
                args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step)
                currentEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                // 3. Cliquer sur Envoyer après un court délai
                handler.postDelayed({
                    val sendRoot = rootInActiveWindow
                    if (sendRoot != null) {
                        val clicked = clickSendButton(sendRoot)
                        sendRoot.recycle()
                        if (!clicked) {
                            Log.w(TAG, "Bouton Envoyer non trouvé pour étape $stepNum")
                        }
                    }

                    // 4. Passer à l'étape suivante
                    currentStepIndex++
                    isProcessingStep = false  // Libérer le verrou

                    if (currentStepIndex >= pendingSteps.size) {
                        Log.d(TAG, "✅ Toutes les étapes saisies")
                        isActive = false
                        onComplete?.invoke()
                        reset()
                    }

                    currentEdit.recycle()
                }, 600)  // Délai avant clic Envoyer

                currentRoot?.recycle()
            } else {
                Log.w(TAG, "Champ de saisie perdu pour étape $stepNum")
                isProcessingStep = false
                currentRoot?.recycle()
            }
        }, 300)  // Délai après effacement avant saisie

        editField.recycle()
        rootNode.recycle()
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true && node.isEditable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun clickSendButton(rootNode: AccessibilityNodeInfo): Boolean {
        val sendLabels = listOf("envoyer", "send", "ok", "valider", "confirm", "suivant", "next", "soumettre")

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (node.isClickable &&
                (node.className?.contains("Button") == true ||
                 sendLabels.any { text.contains(it) || desc.contains(it) })) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = search(child)
                if (found != null) { child.recycle(); return found }
                child.recycle()
            }
            return null
        }

        val btn = search(rootNode)
        return if (btn != null) {
            Log.d(TAG, "Clic bouton: '${btn.text}'")
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            btn.recycle()
            true
        } else {
            false
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrompu")
        isProcessingStep = false
    }

    override fun onDestroy() {
        super.onDestroy()
        reset()
    }
}
