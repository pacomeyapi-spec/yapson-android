package net.yapson.mobile.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal circulaire en mémoire des évènements USSD (service d'accessibilité +
 * runner). Permet de diagnostiquer une tentative : où le service a décroché,
 * ce qu'il a lu, quelle étape a été saisie, etc.
 */
object UssdLog {
    private const val CAPACITY = 200
    private val buf = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(line: String) {
        val ts = fmt.format(Date())
        if (buf.size >= CAPACITY) buf.removeFirst()
        buf.addLast("$ts  $line")
        android.util.Log.d("YAPSON_USSD", line)
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    @Synchronized
    fun clear() { buf.clear() }
}
