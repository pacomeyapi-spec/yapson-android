package net.yapson.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service d'accessibilité qui détecte les fenêtres USSD et saisit automatiquement
 * les réponses étape par étape.
 *
 * Fonctionnement :
 * 1. Détecte l'ouverture d'une fenêtre USSD (dialog système)
 * 2. Vide le champ de saisie existant
 * 3. Saisit la valeur de l'étape courante
 * 4. Clique sur le bouton "Envoyer" / "Send" / "OK"
 * 5. Passe à l'étape suivante
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UssdAccService"

        // Étapes USSD à saisir (alimenté par YapsonService)
        var pendingSteps: MutableList<String> = mutableListOf()
        var currentStepIndex: Int = 0
        var isActive: Boolean = false

        // Callback appelé quand toutes les étapes sont terminées
        var onComplete: (() -> Unit)? = null
        var onError: ((String) -> Unit)? = null

        fun startSequence(steps: List<String>, onDone: () -> Unit, onErr: (String) -> Unit) {
            pendingSteps = steps.drop(1).toMutableList() // Étape 1 déjà lancée via Intent
            currentStepIndex = 0
            isActive = true
            onComplete = onDone
            onError = onErr
            Log.d(TAG, "Séquence démarrée: ${pendingSteps.size} étapes restantes")
        }

        fun reset() {
            pendingSteps.clear()
            currentStepIndex = 0
            isActive = false
            onComplete = null
            onError = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "Service accessibilité connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isActive || pendingSteps.isEmpty()) return
        if (event == null) return

        // Détecter les fenêtres USSD (dialog du système téléphonique)
        val packageName = event.packageName?.toString() ?: ""
        val isPhoneApp = packageName.contains("phone") ||
                         packageName.contains("dialer") ||
                         packageName.contains("com.android.phone") ||
                         packageName.contains("com.samsung.android.dialer") ||
                         packageName.contains("com.google.android.dialer")

        if (!isPhoneApp) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val rootNode = rootInActiveWindow ?: return

        // Chercher un champ de saisie dans la fenêtre USSD
        val editField = findEditText(rootNode)
        if (editField != null) {
            val step = pendingSteps.getOrNull(currentStepIndex) ?: return
            Log.d(TAG, "Fenêtre USSD détectée - Saisie étape ${currentStepIndex + 2}: $step")

            // Vider le champ et saisir la valeur
            editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle()
            args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step)
            editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            // Attendre un court instant puis cliquer sur Envoyer
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                clickSendButton(rootNode)
                currentStepIndex++

                if (currentStepIndex >= pendingSteps.size) {
                    Log.d(TAG, "✅ Toutes les étapes USSD saisies")
                    isActive = false
                    onComplete?.invoke()
                    reset()
                }
            }, 500)
        }

        rootNode.recycle()
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true && node.isEditable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun clickSendButton(rootNode: AccessibilityNodeInfo): Boolean {
        // Chercher bouton "Envoyer", "Send", "OK", "Valider"
        val sendLabels = listOf("envoyer", "send", "ok", "valider", "confirm", "suivant", "next")

        fun searchButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.lowercase() ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            val isClickable = node.isClickable
            val isButton = node.className?.contains("Button") == true

            if (isClickable && (isButton || sendLabels.any { text.contains(it) || contentDesc.contains(it) })) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = searchButton(child)
                if (found != null) return found
                child.recycle()
            }
            return null
        }

        val btn = searchButton(rootNode)
        if (btn != null) {
            Log.d(TAG, "Clic sur bouton: ${btn.text}")
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            btn.recycle()
            return true
        }

        // Fallback: appuyer sur Entrée
        Log.w(TAG, "Bouton Envoyer non trouvé - appui sur Entrée")
        performGlobalAction(GLOBAL_ACTION_BACK)
        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrompu")
        reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        reset()
    }
}
