package pt.ricardo.atempo

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Corre de hora a hora: vai ao Moodle de vez em quando e decide que lembretes mandar. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Moodle.syncIfStale(applicationContext)
        Reminders.run(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("lembretes", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object Reminders {
    const val CH_DAY = "resumo"
    const val CH_DUE = "prazos"
    const val CH_URGENT = "urgente"
    private const val ID_MORNING = 1
    private const val ID_FRIDAY = 2
    private const val ID_TEST = 9
    private val PLUM = 0xFF3B2A5C.toInt()

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DAY, "Resumo do dia e plano de sexta", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_DUE, "Prazos a aproximar-se", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_URGENT, "Entregas para daqui a poucas horas", NotificationManager.IMPORTANCE_HIGH))
    }

    fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS)

    private fun state(c: Context) = c.applicationContext.getSharedPreferences("lembretes", Context.MODE_PRIVATE)

    private fun sent(c: Context, key: String) = state(c).getStringSet("enviados", emptySet())!!.contains(key)

    private fun mark(c: Context, key: String) {
        val p = state(c)
        var set = p.getStringSet("enviados", emptySet())!!.toMutableSet()
        if (set.size > 400) set = set.toList().takeLast(200).toMutableSet()
        set += key
        p.edit().putStringSet("enviados", set).apply()
    }

    private fun once(c: Context, key: String, f: () -> Unit) {
        if (sent(c, key)) return
        mark(c, key)
        f()
    }

    fun run(c: Context, n: ZonedDateTime = now()) {
        if (!canNotify(c)) return
        val h = n.hour
        if (h < 8 || h >= 23) return
        val day = n.toLocalDate().toString()
        val friday = n.dayOfWeek == DayOfWeek.FRIDAY
        val list = Store.deadlines(c).filter { it.active }.sortedBy { it.dueMs }
        val startHour = if (friday) Settings.fridayHour(c) else Settings.morningHour(c)

        // 1. O resumo da manhã (à sexta, o plano do dia de trabalho)
        if (h >= startHour) once(c, "manha:$day") {
            if (friday) fridayPlan(c, list, n) else morning(c, list, n)
        }
        if (h < startHour) return

        // 2. Cada prazo vai subindo de tom à medida que se aproxima
        list.forEach { d ->
            val days = d.daysLeft(n.toLocalDate())
            val mins = d.minutesLeft(n)
            if (d.isExam) { examStep(c, d, days, mins, h); return@forEach }
            when {
                mins < 0 -> Unit
                days == 0L && mins <= 60 -> once(c, "${d.id}:1h") { due(c, d, "⏰ Falta menos de 1 hora", urgent = true) }
                days == 0L && mins <= 180 -> once(c, "${d.id}:3h") { due(c, d, "⏳ Faltam ${mins / 60} horas", urgent = true) }
                days == 0L && mins <= 360 -> once(c, "${d.id}:6h") { due(c, d, "📌 É hoje, às ${Fmt.time(d.effective)}", urgent = false) }
                days in 1L..2L && h >= 18 -> once(c, "${d.id}:2d") {
                    due(c, d, if (days == 1L) "🌙 É amanhã" else "Faltam 2 dias", urgent = false)
                }
                days in 3L..7L -> once(c, "${d.id}:7d") { due(c, d, "🗓️ ${Fmt.countdown(d, n).replaceFirstChar { it.uppercase() }}", urgent = false) }
            }
        }

        // 3. Sexta: um toque a meio da tarde e o fecho do dia
        if (friday && h >= 15) once(c, "sexta-tarde:$day") { fridayCheck(c, list) }
        if (friday && h >= 19) once(c, "sexta-noite:$day") { fridayClose(c, list) }
    }

    /** Para testes, os avisos começam mais cedo e falam de estudar, não de entregar. */
    private fun examStep(c: Context, d: Deadline, days: Long, mins: Long, h: Int) {
        val k = d.kindLabel.lowercase()
        when {
            mins < 0 -> Unit
            days == 0L && mins <= 120 -> once(c, "${d.id}:2h") {
                exam(c, d, "📝 ${d.kindLabel} daqui a pouco", "Às ${Fmt.time(d.effective)}. Respira fundo, tu sabes isto.", urgent = true)
            }
            days == 1L && h >= 18 -> once(c, "${d.id}:1d") {
                exam(c, d, "🌙 Amanhã é o $k", "Prepara o material e a mochila hoje, e dorme bem. ${Fmt.due(d).replaceFirstChar { it.uppercase() }}.", urgent = false)
            }
            days in 2L..3L -> once(c, "${d.id}:3d") {
                exam(c, d, "📚 Faltam $days dias para o $k", "A partir de hoje, estudo a sério. Um bocadinho por dia rende mais do que tudo na véspera.", urgent = false)
            }
            days in 4L..7L -> once(c, "${d.id}:7d") {
                exam(c, d, "📝 $days dias para o $k", "Boa altura para fazer um resumo da matéria, à tua maneira (mapas, esquemas, desenhos).", urgent = false)
            }
            days in 8L..14L -> once(c, "${d.id}:14d") {
                exam(c, d, "📝 ${d.kindLabel} daqui a duas semanas", "Começa a rever aos poucos. Vinte minutos chegam.", urgent = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun exam(c: Context, d: Deadline, title: String, body: String, urgent: Boolean) {
        val id = 1000 + (d.id.hashCode() and 0xFFFF)
        val text = "${d.title} · ${d.course}\n$body"
        val b = builder(c, if (urgent) CH_URGENT else CH_DUE, title, text, openApp(c, id, "abrir" to d.id))
        if (!urgent && !d.plannedToday()) b.addAction(0, "Estudo hoje", broadcast(c, id + 2, ActionReceiver.PLAN, d.id))
        if (urgent) b.setPriority(NotificationCompat.PRIORITY_HIGH)
        channels(c)
        NotificationManagerCompat.from(c).notify(id, b.build())
    }

    // ---------- textos ----------

    private fun line(d: Deadline, n: ZonedDateTime) = "• ${d.title} · ${d.course.ifBlank { "—" }} · ${Fmt.countdown(d, n)}"

    @SuppressLint("MissingPermission")
    fun morning(c: Context, list: List<Deadline>, n: ZonedDateTime) {
        val late = list.filter { it.isLate(n) }
        val work = list.filter { !it.isExam }
        val todayL = work.filter { !it.isLate(n) && it.daysLeft(n.toLocalDate()) == 0L }
        val week = work.filter { !it.isLate(n) && it.daysLeft(n.toLocalDate()) in 1L..7L }
        val exams = list.filter { it.isExam && it.daysLeft(n.toLocalDate()) <= 7L }
        if (late.isEmpty() && todayL.isEmpty() && week.isEmpty() && exams.isEmpty()) return
        val examToday = exams.firstOrNull { it.daysLeft(n.toLocalDate()) == 0L }

        val title = when {
            examToday != null -> "📝 Hoje: ${examToday.title}, às ${Fmt.time(examToday.effective)}"
            late.isNotEmpty() -> "🔴 ${Fmt.plural(late.size, "trabalho em atraso", "trabalhos em atraso")}"
            todayL.isNotEmpty() -> "📌 Hoje entregas: ${todayL.first().title}"
            else -> "☀️ Esta semana: ${Fmt.plural(week.size, "entrega", "entregas")}"
        }
        val lines = mutableListOf<String>()
        if (late.isNotEmpty()) { lines += "Em atraso (ainda dá para entregar?):"; late.forEach { lines += line(it, n) } }
        if (todayL.isNotEmpty()) { lines += "Hoje:"; todayL.forEach { lines += line(it, n) } }
        if (exams.isNotEmpty()) { lines += "Testes e exames:"; exams.forEach { lines += line(it, n) } }
        if (week.isNotEmpty()) { lines += "Próximos 7 dias:"; week.take(5).forEach { lines += line(it, n) } }

        val slots = Store.slots(c)
        val minuteNow = n.hour * 60 + n.minute
        val next = (late + todayL + week).firstOrNull()
        FreeTime.best(slots, n.toLocalDate(), minuteNow)?.let { w ->
            if (next != null) lines += "\n✨ Livre hoje das ${hm(maxOf(w.start, minuteNow))} às ${hm(w.end)}. Bom momento para «${next.title}»."
        }
        if (todayL.isNotEmpty()) FreeTime.lateCommitment(slots, n.toLocalDate())?.let { s ->
            lines += "🎻 Hoje tens ${s.title} até às ${hm(s.end)}. Entrega antes de saíres."
        }
        post(c, ID_MORNING, CH_DAY, title, lines.joinToString("\n"), openApp(c, 1))
    }

    @SuppressLint("MissingPermission")
    fun fridayPlan(c: Context, list: List<Deadline>, n: ZonedDateTime) {
        val pending = list.filter { it.daysLeft(n.toLocalDate()) <= 21 }
        val lines = mutableListOf<String>()
        if (pending.isEmpty()) {
            lines += "Nada com prazo nas próximas 3 semanas. Bom dia para avançar um projecto com calma, ou para descansar."
        } else {
            lines += "Escolhe 2 ou 3 para hoje, a começar pelos de cima:"
            pending.take(6).forEach { lines += line(it, n) }
        }
        lines += "\n🔎 E espreita as páginas «Datas e Deadlines» do Moodle, que nem tudo aparece no calendário."
        post(c, ID_FRIDAY, CH_DAY, "🎨 Sexta é dia de trabalho", lines.joinToString("\n"), openApp(c, 2, "aba" to "hoje"))
    }

    @SuppressLint("MissingPermission")
    private fun fridayCheck(c: Context, list: List<Deadline>) {
        val planned = list.filter { it.plannedToday() }
        if (planned.isNotEmpty()) {
            val t = planned.first()
            post(c, ID_FRIDAY, CH_DAY, "🖌️ Como vai «${t.title}»?",
                if (planned.size > 1) "E ainda ${Fmt.plural(planned.size - 1, "outro", "outros")} na lista de hoje. Uma coisa de cada vez." else "Ainda há tarde pela frente.",
                openApp(c, 3, "aba" to "hoje"))
        } else if (list.isNotEmpty()) {
            post(c, ID_FRIDAY, CH_DAY, "🖌️ Ainda vais a tempo",
                "Escolhe só um trabalho para esta tarde: «${list.first().title}» é o que vence primeiro.",
                openApp(c, 3, "aba" to "hoje"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun fridayClose(c: Context, list: List<Deadline>) {
        val planned = list.filter { it.plannedToday() }
        if (planned.isEmpty()) return
        post(c, ID_FRIDAY, CH_DAY, "🌇 Fim do dia de trabalho",
            "O que ficou entregue? Marca na app. O que não ficou passa para a próxima vez, sem drama.",
            openApp(c, 4, "aba" to "hoje"))
    }

    @SuppressLint("MissingPermission")
    private fun due(c: Context, d: Deadline, title: String, urgent: Boolean) {
        val text = "${d.title} · ${d.course}\n${Fmt.due(d).replaceFirstChar { it.uppercase() }}" +
            if (d.midnightTrap) "\n(o Moodle diz meia-noite, por isso é na noite anterior)" else ""
        val id = 1000 + (d.id.hashCode() and 0xFFFF)
        val b = builder(c, if (urgent) CH_URGENT else CH_DUE, title, text, openApp(c, id, "abrir" to d.id))
            .addAction(0, "✓ Entregue", broadcast(c, id + 1, ActionReceiver.DONE, d.id))
        if (!urgent && !d.plannedToday()) b.addAction(0, "Faço hoje", broadcast(c, id + 2, ActionReceiver.PLAN, d.id))
        if (urgent) b.setPriority(NotificationCompat.PRIORITY_HIGH)
        channels(c)
        NotificationManagerCompat.from(c).notify(id, b.build())
    }

    @SuppressLint("MissingPermission")
    fun test(c: Context) {
        post(c, ID_TEST, CH_DAY, "🦊 Olá, ${Settings.name(c)}!", "É assim que os lembretes vão aparecer. Discretos, mas sem te deixar esquecer.", openApp(c, 9))
    }

    // ---------- canalização ----------

    private fun builder(c: Context, ch: String, title: String, text: String, open: PendingIntent) =
        NotificationCompat.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(PLUM)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() } ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)

    @SuppressLint("MissingPermission")
    private fun post(c: Context, id: Int, ch: String, title: String, text: String, open: PendingIntent) {
        if (!canNotify(c)) return
        channels(c)
        NotificationManagerCompat.from(c).notify(id, builder(c, ch, title, text, open).build())
    }

    fun cancel(c: Context, deadlineId: String) {
        NotificationManagerCompat.from(c).cancel(1000 + (deadlineId.hashCode() and 0xFFFF))
    }

    private fun openApp(c: Context, code: Int, extra: Pair<String, String>? = null): PendingIntent {
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (extra != null) i.putExtra(extra.first, extra.second)
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun broadcast(c: Context, code: Int, action: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, code,
            Intent(c, ActionReceiver::class.java).setAction(action).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        when (i.action) {
            DONE -> Store.edit(c, id) { it.copy(doneAt = System.currentTimeMillis()) }
            PLAN -> Store.edit(c, id) { it.copy(planDay = today().toString()) }
        }
        Reminders.cancel(c, id)
    }

    companion object {
        const val DONE = "pt.ricardo.atempo.ENTREGUE"
        const val PLAN = "pt.ricardo.atempo.FACO_HOJE"
    }
}
