package pt.ricardo.atempo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Vê no GitHub se há uma versão nova e avisa uma vez por versão. */
object Updater {
    private const val REPO = "RCruzDeCarvalho/atempo-app"
    /** Link fixo para a última versão (o ficheiro a-tempo.apk de cada compilação). */
    const val LATEST_APK = "https://github.com/$REPO/releases/latest/download/a-tempo.apk"
    private const val ID_UPDATE = 5

    data class Update(val versionName: String, val versionCode: Int)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("actualizacoes", Context.MODE_PRIVATE)

    fun pending(c: Context): Update? {
        val p = prefs(c)
        val code = p.getInt("code", 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        return Update(p.getString("name", "") ?: "", code)
    }

    fun check(c: Context, force: Boolean = false): Update? {
        val p = prefs(c)
        if (!force && System.currentTimeMillis() - p.getLong("checked", 0L) < 6 * 3_600_000L) return pending(c)
        return try {
            val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val body = o.optString("body")
            val code = Regex("versionCode=(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val name = Regex("versionName=([\\w.]+)").find(body)?.groupValues?.get(1) ?: o.optString("name")
            p.edit().putLong("checked", System.currentTimeMillis()).putInt("code", code).putString("name", name).apply()
            pending(c)
        } catch (_: Exception) {
            pending(c)
        }
    }

    fun download(c: Context) = openUrl(c, LATEST_APK)

    @SuppressLint("MissingPermission")
    fun notifyIfNew(c: Context) {
        val u = check(c) ?: return
        val p = prefs(c)
        if (p.getInt("notified", 0) >= u.versionCode || !Reminders.canNotify(c)) return
        val pi = PendingIntent.getActivity(
            c, 50, Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_APK)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        Reminders.channels(c)
        NotificationManagerCompat.from(c).notify(
            ID_UPDATE,
            NotificationCompat.Builder(c, Reminders.CH_DAY)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF3B2A5C.toInt())
                .setContentTitle("✨ Há uma versão nova da A Tempo")
                .setContentText("Toca para descarregar a versão ${u.versionName} e instala por cima.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
        p.edit().putInt("notified", u.versionCode).apply()
    }
}
