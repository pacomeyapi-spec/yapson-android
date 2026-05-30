package net.yapson.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import net.yapson.mobile.utils.UssdLog
import net.yapson.mobile.utils.UssdRunner

/**
 * Service d'accessibilité — pilote les dialogues USSD opérateur.
 *
 * Quand une session est active (UssdRunner.isBusy()), on parcourt TOUTES les
 * fenêtres (pas seulement rootInActiveWindow), on repère le dialogue USSD
 * (champ de saisie + bouton, ou AlertDialog, ou paquet télécom) et on agit.
 * Le scan multi-fenêtres est indispensable sur Transsion (Tecno/Infinix/itel),
 * très répandus en Côte d'Ivoire : le dialogue USSD n'y est pas dans la fenêtre
 * active.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var isConnected: Boolean = false; private set
        private val SEND_LABELS = listOf("send", "envoyer", "valider", "suivant", "next", "ok", "oui", "yes", "envoi", "confirmer")
        private val DISMISS_LABELS = listOf("annuler", "cancel", "fermer", "close", "dismiss", "no", "non", "retour")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
        }
        isConnected = true
        UssdLog.add("✅ Service d'accessibilité CONNECTÉ")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isConnected = false
        UssdLog.add("⛔ Service d'accessibilité DÉCONNECTÉ")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !UssdRunner.isBusy()) return
        val pkg = event.packageName?.toString() ?: ""
        if (isOwnOrLauncher(pkg)) return

        val sinceDial = System.currentTimeMillis() - UssdRunner.dialStartedAt
        if (UssdRunner.dialStartedAt > 0 && sinceDial < 800) return

        try {
            for ((idx, root) in collectCandidates().withIndex()) {
                if (tryProcess(root, idx)) return
            }
        } catch (e: Exception) {
            UssdLog.add("💥 Exception onEvent: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun isOwnOrLauncher(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg == packageName) return true
        val low = pkg.lowercase()
        return low.contains("launcher") || low.contains("nova") || low.contains("googlequicksearchbox") ||
               low == "android" || low == "com.android.systemui"
    }

    private fun collectCandidates(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { windows?.forEach { w -> w?.root?.let { out.add(it) } } } catch (_: Exception) {}
        }
        rootInActiveWindow?.let { if (!out.contains(it)) out.add(it) }
        return out
    }

    private fun tryProcess(root: AccessibilityNodeInfo, idx: Int): Boolean {
        val pkg = root.packageName?.toString() ?: ""
        if (isOwnOrLauncher(pkg)) return false

        val editable = findEditable(root)
        val buttons = ArrayList<AccessibilityNodeInfo>()
        collectButtons(root, buttons)
        val rootCls = root.className?.toString() ?: ""

        val pkgTel = pkg.contains("phone", true) || pkg.contains("dialer", true) ||
                     pkg.contains("telecom", true) || pkg.contains("ussd", true) || pkg.contains("mmitelephony", true)
        val btnMatch = buttons.any { labelMatches(it, SEND_LABELS) || labelMatches(it, DISMISS_LABELS) }
        val isDialogCls = rootCls.contains("Dialog", true) || rootCls.contains("Popup", true)
        if (!((editable != null && btnMatch) || pkgTel || isDialogCls)) return false

        val promptText = collectText(root, editable)
        val isLoadingShape = editable == null && buttons.isEmpty()
        if (isLoadingShape || isLoadingMessage(promptText)) {
            UssdLog.add("💤 Écran de chargement — attente du vrai menu")
            return false
        }

        UssdLog.add("✓ Dialogue USSD — '${promptText.take(100).replace('\n', ' ')}' input=${editable != null}")
        UssdRunner.onUssdDialog(promptText, editable != null) { input, submit, dismiss ->
            if (submit && input != null && editable != null) {
                setText(editable, input)
                if (!clickByLabels(buttons, SEND_LABELS) && buttons.isNotEmpty()) clickNode(buttons[0])
            } else if (dismiss) {
                if (!clickByLabels(buttons, DISMISS_LABELS)) performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        return true
    }

    private fun collectText(node: AccessibilityNodeInfo?, editable: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val cls = n.className?.toString() ?: ""
            if (!cls.contains("Button", true) && !cls.contains("EditText", true) && !n.isEditable) {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    if (sb.isNotEmpty()) sb.append(" "); sb.append(it)
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(node)
        return sb.toString().trim()
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable || (node.className?.toString() ?: "").contains("EditText", true)) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i))?.let { return it }
        return null
    }

    private fun collectButtons(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Button", true) || (node.isClickable && !node.text.isNullOrEmpty())) out.add(node)
        for (i in 0 until node.childCount) collectButtons(node.getChild(i), out)
    }

    private fun isLoadingMessage(text: String): Boolean {
        if (text.isEmpty()) return false
        val low = text.lowercase()
        return low.contains("exécution du code ussd") || low.contains("execution du code ussd") ||
               low.contains("running ussd") || low.contains("ussd code running") ||
               low.contains("veuillez patienter") || low.contains("please wait") ||
               low.contains("en cours d'envoi") || low.contains("connexion en cours")
    }

    private fun labelMatches(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val t = node.text?.toString()?.trim()?.lowercase() ?: return false
        return labels.any { t == it || t.contains(it) }
    }

    private fun clickByLabels(buttons: List<AccessibilityNodeInfo>, labels: List<String>): Boolean {
        for (label in labels) for (b in buttons) {
            val t = b.text?.toString()?.trim()?.lowercase() ?: continue
            if (t == label) { clickNode(b); return true }
        }
        for (label in labels) for (b in buttons) {
            val t = b.text?.toString()?.trim()?.lowercase() ?: continue
            if (t.contains(label)) { clickNode(b); return true }
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }
}
