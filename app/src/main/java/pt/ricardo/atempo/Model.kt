package pt.ricardo.atempo

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

val zone: ZoneId get() = ZoneId.systemDefault()
fun now(): ZonedDateTime = ZonedDateTime.now(zone)
fun today(): LocalDate = LocalDate.now(zone)

fun granted(c: Context, perm: String) =
    ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED

/** Um trabalho com prazo, vindo do Moodle ou acrescentado à mão. */
const val KIND_WORK = "trabalho"
const val KIND_TEST = "teste"
const val KIND_EXAM = "exame"

data class Deadline(
    val id: String = "manual:" + UUID.randomUUID().toString(),
    val title: String,
    val course: String,
    val dueMs: Long,
    val description: String = "",
    val link: String = "",
    val fromMoodle: Boolean = false,
    val doneAt: Long = 0L,
    val droppedAt: Long = 0L,
    val goneFromMoodle: Boolean = false,  // desapareceu do Moodle antes do prazo
    val planDay: String = "",             // dia em que ela escolheu fazê-lo
    val created: Long = System.currentTimeMillis(),
    val kind: String = KIND_WORK,         // trabalho, teste ou exame
    val place: String = ""                // sala, para testes e exames
) {
    val isExam get() = kind != KIND_WORK
    val kindLabel get() = when (kind) { KIND_TEST -> "Teste"; KIND_EXAM -> "Exame"; else -> "Trabalho" }

    /** Um teste que já passou sai da lista sozinho (não fica "atrasado"). */
    val examOver get() = isExam && System.currentTimeMillis() > dueMs + 3 * 3_600_000L

    val active get() = doneAt == 0L && droppedAt == 0L && !goneFromMoodle && !examOver

    val due: ZonedDateTime get() = Instant.ofEpochMilli(dueMs).atZone(zone)

    /** Prazos à meia-noite acabam, na prática, na noite anterior. */
    val midnightTrap: Boolean get() = !isExam && due.hour == 0 && due.minute == 0

    /** O momento real em que deixa de dar para entregar. */
    val effective: ZonedDateTime get() = if (midnightTrap) due.minusMinutes(1) else due

    val effectiveDate: LocalDate get() = effective.toLocalDate()

    fun daysLeft(t: LocalDate = today()): Long = ChronoUnit.DAYS.between(t, effectiveDate)

    fun minutesLeft(n: ZonedDateTime = now()): Long = Duration.between(n, effective).toMinutes()

    fun isLate(n: ZonedDateTime = now()) = active && !isExam && minutesLeft(n) < 0

    fun plannedToday() = planDay == today().toString()

    /** Escolhido para um dia que já passou e ainda não está feito. */
    fun leftover() = active && planDay.isNotEmpty() && planDay < today().toString()
}

/** Uma aula (ou outra coisa fixa, como a Tuna) no horário semanal. */
data class Slot(
    val id: String = UUID.randomUUID().toString(),
    val day: Int,          // 1 = segunda … 7 = domingo
    val start: Int,        // minutos desde a meia-noite
    val end: Int,
    val title: String,
    val isClass: Boolean = true
) {
    val startTime: LocalTime get() = LocalTime.of(start / 60, start % 60)
    val endTime: LocalTime get() = LocalTime.of(end / 60, end % 60)
}

fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

object Store {
    @Volatile private var cache: List<Deadline>? = null
    @Volatile private var slotCache: List<Slot>? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("dados", Context.MODE_PRIVATE)

    fun invalidate() { cache = null; slotCache = null }

    // ---------- prazos ----------
    fun deadlines(c: Context): List<Deadline> = cache ?: loadDeadlines(c).also { cache = it }

    fun get(c: Context, id: String) = deadlines(c).firstOrNull { it.id == id }

    @Synchronized
    fun update(c: Context, f: (List<Deadline>) -> List<Deadline>) {
        val n = f(deadlines(c))
        val a = JSONArray()
        n.forEach { d ->
            a.put(JSONObject().apply {
                put("id", d.id); put("title", d.title); put("course", d.course); put("due", d.dueMs)
                put("desc", d.description); put("link", d.link); put("moodle", d.fromMoodle)
                put("done", d.doneAt); put("dropped", d.droppedAt); put("gone", d.goneFromMoodle)
                put("plan", d.planDay); put("created", d.created)
                put("kind", d.kind); put("place", d.place)
            })
        }
        prefs(c).edit().putString("prazos", a.toString()).commit()
        cache = n
    }

    fun add(c: Context, d: Deadline) = update(c) { it + d }
    fun edit(c: Context, id: String, f: (Deadline) -> Deadline) = update(c) { l -> l.map { if (it.id == id) f(it) else it } }
    fun remove(c: Context, id: String) = update(c) { l -> l.filterNot { it.id == id } }

    private fun loadDeadlines(c: Context): List<Deadline> {
        val s = prefs(c).getString("prazos", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Deadline(
                    id = o.getString("id"), title = o.getString("title"), course = o.optString("course"),
                    dueMs = o.getLong("due"), description = o.optString("desc"), link = o.optString("link"),
                    fromMoodle = o.optBoolean("moodle"), doneAt = o.optLong("done"), droppedAt = o.optLong("dropped"),
                    goneFromMoodle = o.optBoolean("gone"), planDay = o.optString("plan"),
                    created = o.optLong("created", System.currentTimeMillis()),
                    kind = o.optString("kind", KIND_WORK).ifBlank { KIND_WORK }, place = o.optString("place")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ---------- horário ----------
    fun slots(c: Context): List<Slot> = slotCache ?: loadSlots(c).also { slotCache = it }

    @Synchronized
    fun updateSlots(c: Context, f: (List<Slot>) -> List<Slot>) = saveSlots(c, f(slots(c)))

    private fun saveSlots(c: Context, list: List<Slot>) {
        val n = list.sortedWith(compareBy({ it.day }, { it.start }))
        val a = JSONArray()
        n.forEach { s ->
            a.put(JSONObject().apply {
                put("id", s.id); put("day", s.day); put("start", s.start); put("end", s.end)
                put("title", s.title); put("aula", s.isClass)
            })
        }
        prefs(c).edit().putString("horario", a.toString()).commit()
        slotCache = n
    }

    private fun loadSlots(c: Context): List<Slot> {
        val s = prefs(c).getString("horario", null)
        if (s == null) {
            // Primeira vez: o horário da Turma J do 1.º semestre de 2026/27
            saveSlots(c, DefaultSchedule.slots)
            return slotCache ?: DefaultSchedule.slots
        }
        return try {
            val a = JSONArray(s)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Slot(o.getString("id"), o.getInt("day"), o.getInt("start"), o.getInt("end"), o.getString("title"), o.optBoolean("aula", true))
            }
        } catch (_: Exception) { emptyList() }
    }
}

object DefaultSchedule {
    private fun t(h: Int, m: Int) = h * 60 + m
    val slots = listOf(
        Slot(day = 1, start = t(11, 30), end = t(13, 15), title = "Oficina de Escultura"),
        Slot(day = 1, start = t(14, 30), end = t(16, 15), title = "Oficina de Arte Multimédia"),
        Slot(day = 1, start = t(16, 45), end = t(18, 30), title = "Desenho I"),
        Slot(day = 1, start = t(18, 30), end = t(22, 0), title = "Ensaio da Tuna", isClass = false),
        Slot(day = 2, start = t(10, 0), end = t(11, 0), title = "Projeto em Design I (Metodologia)"),
        Slot(day = 2, start = t(11, 15), end = t(13, 15), title = "Projeto em Design I (Gráfico)"),
        Slot(day = 2, start = t(14, 30), end = t(16, 30), title = "Teoria da Imagem e Cultura Visual"),
        Slot(day = 2, start = t(16, 45), end = t(18, 30), title = "Concept Art I"),
        Slot(day = 3, start = t(11, 45), end = t(13, 30), title = "Projeto em Design I (Produto)"),
        Slot(day = 3, start = t(14, 30), end = t(16, 30), title = "História da Arte I"),
        Slot(day = 4, start = t(11, 0), end = t(13, 15), title = "Oficina de Pintura"),
        Slot(day = 4, start = t(19, 0), end = t(22, 0), title = "Ensaio da Tuna", isClass = false)
    )
}

/** Definições guardadas no telemóvel (o link do Moodle nunca sai daqui). */
object Settings {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("definicoes", Context.MODE_PRIVATE)

    fun icsUrl(c: Context) = p(c).getString("ics", "") ?: ""
    /** Limpa espaços e quebras de linha que vêm ao copiar, e aceita links webcal://. */
    fun setIcsUrl(c: Context, v: String) {
        val clean = v.replace(Regex("\\s"), "").replace(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
        p(c).edit().putString("ics", clean).apply()
    }

    fun turma(c: Context) = p(c).getString("turma", "J") ?: "J"
    fun setTurma(c: Context, v: String) = p(c).edit().putString("turma", v.trim().uppercase().take(1)).apply()

    fun name(c: Context) = p(c).getString("nome", "Maria") ?: "Maria"
    fun setName(c: Context, v: String) = p(c).edit().putString("nome", v.trim()).apply()

    fun morningHour(c: Context) = p(c).getInt("manha", 9)
    fun setMorningHour(c: Context, v: Int) = p(c).edit().putInt("manha", v.coerceIn(6, 12)).apply()

    fun fridayHour(c: Context) = p(c).getInt("sexta", 10)
    fun setFridayHour(c: Context, v: Int) = p(c).edit().putInt("sexta", v.coerceIn(7, 13)).apply()

    fun lastSync(c: Context) = p(c).getLong("sync", 0L)
    fun syncError(c: Context) = p(c).getString("erro", "") ?: ""
    fun setSyncResult(c: Context, ok: Boolean, msg: String) {
        val e = p(c).edit().putString("erro", if (ok) "" else msg)
        if (ok) e.putLong("sync", System.currentTimeMillis())
        e.apply()
    }

    /** Base do Moodle, tirada do próprio link (ex.: https://moodle2627.ipl.pt). */
    fun moodleBase(c: Context): String {
        val u = icsUrl(c)
        return Regex("^(https?://[^/]+)").find(u)?.groupValues?.get(1) ?: ""
    }
}
