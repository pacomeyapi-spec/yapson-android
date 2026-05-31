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
        // Sélecteur de SIM (dual-SIM) — apparaît sur Transsion & co. quand le compte n'est pas imposé
        private val SIM_ROW_REGEX = Regex("""sim\s*0?([12])""", RegexOption.IGNORE_CASE)
        private val SIM_PICKER_HINTS = listOf(
            "appeler avec", "call with", "select sim", "select a sim", "choisir une", "choisissez",
            "sélectionner", "selectionner", "carte sim", "choose sim", "quelle sim", "which sim"
        )
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

        // Sélecteur de SIM : priorité absolue, dès son apparition (avant tout délai/filtrage).
        if (!UssdRunner.simPickerDone && UssdRunner.desiredSimSlot >= 0) {
            try { for (root in collectCandidates()) if (tryProcessSimPicker(root)) return } catch (_: Exception) {}
        }

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
        UssdRunner.simPickerDone = true // un vrai dialogue USSD est là : plus besoin de guetter le sélecteur SIM
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

    /**
     * Détecte le sélecteur de SIM (dual-SIM) et clique la ligne correspondant au
     * slot voulu. Indispensable quand le système ignore EXTRA_PHONE_ACCOUNT_HANDLE
     * et demande « Appeler avec quelle SIM ? » (fréquent sur Transsion).
     */
    private fun tryProcessSimPicker(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) return false
        val slot = UssdRunner.desiredSimSlot
        if (slot < 0) return false
        // Un dialogue avec champ de saisie = dialogue USSD, surtout pas un sélecteur SIM.
        if (findEditable(root) != null) return false

        val fullText = collectText(root, null).lowercase()
        if (fullText.isEmpty()) return false
        val telLike = pkg.contains("telecom", true) || pkg.contains("dialer", true) || pkg.contains("phone", true)
        val looksLikePicker = SIM_PICKER_HINTS.any { fullText.contains(it) } ||
                (fullText.contains("sim") && SIM_ROW_REGEX.containsMatchIn(fullText) &&
                 (fullText.contains("appel") || fullText.contains("call") || telLike))
        if (!looksLikePicker) return false

        val textNodes = ArrayList<AccessibilityNodeInfo>()
        gatherTextNodes(root, textNodes)
        if (textNodes.isEmpty()) return false

        val names = UssdRunner.simNameHints()
        var target: AccessibilityNodeInfo? = null

        // 1) Par nom d'opérateur / d'affichage de la SIM voulue.
        if (names.isNotEmpty()) {
            target = textNodes.firstOrNull { n ->
                val t = n.text?.toString()?.trim()?.lowercase() ?: return@firstOrNull false
                names.any { it.isNotEmpty() && t.contains(it) }
            }
        }
        // 2) Par libellé « SIM 1 / SIM 2 ».
        if (target == null) {
            target = textNodes.firstOrNull { n ->
                val m = SIM_ROW_REGEX.find(n.text?.toString() ?: "")
                (m?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)) == slot
            }
        }
        // 3) Repli : n-ième ligne « SIM … » dans l'ordre d'affichage.
        if (target == null) {
            val simRows = textNodes.filter { SIM_ROW_REGEX.containsMatchIn(it.text?.toString() ?: "") }
            target = simRows.getOrNull(slot)
        }
        if (target == null) {
            UssdLog.add("🔎 Sélecteur SIM détecté mais ligne du slot $slot introuvable")
            return false
        }

        UssdLog.add("📲 Sélecteur SIM → clic '${target.text}' (slot $slot)")
        clickNode(target)
        UssdRunner.simPickerDone = true
        return true
    }

    private fun gatherTextNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (!node.text.isNullOrBlank()) out.add(node)
        for (i in 0 until node.childCount) gatherTextNodes(node.getChild(i), out)
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
