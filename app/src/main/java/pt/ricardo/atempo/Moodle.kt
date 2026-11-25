package pt.ricardo.atempo

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Lê o calendário exportado do Moodle (formato iCal) e junta-o aos prazos guardados. */
object Moodle {
    data class Event(
        val uid: String,
        val summary: String,
        val description: String,
        val startMs: Long,
        val category: String
    )

    // ---------- leitura do ficheiro ----------

    fun parse(text: String): List<Event> {
        // Linhas dobradas: as que começam por espaço continuam a anterior
        val lines = mutableListOf<String>()
        text.replace("\r\n", "\n").replace("\r", "\n").split("\n").forEach { l ->
            if ((l.startsWith(" ") || l.startsWith("\t")) && lines.isNotEmpty()) lines[lines.size - 1] += l.substring(1)
            else lines += l
        }
        val out = mutableListOf<Event>()
        var cur: MutableMap<String, Pair<String, String>>? = null   // chave -> (parâmetros, valor)
        for (l in lines) {
            when {
                l == "BEGIN:VEVENT" -> cur = mutableMapOf()
                l == "END:VEVENT" -> {
                    cur?.let { m ->
                        val uid = m["UID"]?.second ?: return@let
                        val start = m["DTSTART"]?.let { parseDate(it.first, it.second) } ?: return@let
                        out += Event(
                            uid = uid,
                            summary = unescape(m["SUMMARY"]?.second ?: ""),
                            description = unescape(m["DESCRIPTION"]?.second ?: ""),
                            startMs = start,
                            category = unescape(m["CATEGORIES"]?.second ?: "")
                        )
                    }
                    cur = null
                }
                cur != null -> {
                    val i = l.indexOf(':')
                    if (i > 0) {
                        val head = l.substring(0, i)
                        val key = head.substringBefore(';').uppercase()
                        val params = if (head.contains(';')) head.substringAfter(';') else ""
                        cur[key] = params to l.substring(i + 1)
                    }
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s
        .replace("\\n", "\n").replace("\\N", "\n")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
        .trim()

    private fun parseDate(params: String, v: String): Long? = try {
        when {
            params.contains("VALUE=DATE") || v.length == 8 ->
                // Só a data: conta até ao fim do dia
                LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
            v.endsWith("Z") ->
                LocalDateTime.parse(v.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            else -> {
                val tz = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)
                val z = try { if (tz != null) ZoneId.of(tz) else zone } catch (_: Exception) { zone }
                LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(z).toInstant().toEpochMilli()
            }
        }
    } catch (_: Exception) { null }

    // ---------- limpeza dos textos ----------

    private val turmaRx = Regex("TURMA\\s*([A-Z])\\b", RegexOption.IGNORE_CASE)
    private val turmaStrip = Regex("[\\s_\\-–:]*\\(?TURMA\\s*[A-Z]\\)?\\b", RegexOption.IGNORE_CASE)
    private val suffixes = listOf(
        " is due", " closes", " deve ser entregue", " termina", " fecha", " - prazo", " prazo"
    )
    private val opens = listOf(" opens", " abre", " começa")

    /** Eventos de abertura não são prazos. */
    fun isOpening(summary: String) = opens.any { summary.lowercase().endsWith(it) }

    /** É de outra turma? (sem turma no nome, é para todos) */
    fun otherTurma(summary: String, turma: String): Boolean {
        val m = turmaRx.find(summary) ?: return false
        return !m.groupValues[1].equals(turma, ignoreCase = true)
    }

    fun cleanTitle(summary: String): String {
        var s = summary.trim()
        suffixes.forEach { suf -> if (s.lowercase().endsWith(suf)) s = s.dropLast(suf.length) }
        s = s.replace(turmaStrip, "").trim(' ', '_', '-', '–', ':')
        s = s.replace(Regex("\\s*_\\s*"), " ").replace(Regex("\\s{2,}"), " ").trim()
        // TUDO EM MAIÚSCULAS cansa a vista: passa a frase normal
        val letters = s.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) {
            s = s.lowercase().replaceFirstChar { it.uppercase() }
        }
        return s.ifBlank { summary }
    }

    /** "PeDI-9006307-202627-Projeto em Design I" → "Projeto em Design I" */
    fun cleanCourse(cat: String): String {
        val parts = cat.split("-", limit = 4)
        return (if (parts.size == 4) parts[3] else cat).trim()
    }

    private fun firstLink(desc: String) =
        Regex("https?://\\S+").find(desc)?.value?.trimEnd('.', ',', ')', ']') ?: ""

    /** Tira da descrição o bloco "Links: [1] …" que o Moodle acrescenta. */
    private fun cleanDescription(desc: String) =
        desc.replace(Regex("\\[\\d+]"), "").substringBefore("Links:").trim()

    // ---------- sincronização ----------

    data class Result(val ok: Boolean, val message: String, val added: Int = 0)

    @Volatile private var running = false

    fun syncIfStale(c: Context, maxAgeMinutes: Long = 180): Result? {
        if (Settings.icsUrl(c).isBlank()) return null
        val age = (System.currentTimeMillis() - Settings.lastSync(c)) / 60_000L
        return if (age >= maxAgeMinutes) sync(c) else null
    }

    @Synchronized
    fun sync(c: Context): Result {
        val url = Settings.icsUrl(c)
        if (url.isBlank()) return Result(false, "Falta o link do calendário do Moodle.")
        if (running) return Result(false, "Já está a sincronizar.")
        running = true
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "ATempo/" + BuildConfig.VERSION_NAME)
            }
            val code = conn.responseCode
            if (code != 200) {
                val r = Result(false, "O Moodle respondeu com erro $code. O link pode ter expirado.")
                Settings.setSyncResult(c, false, r.message)
                return r
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!text.contains("BEGIN:VCALENDAR")) {
                val r = Result(false, "O link não devolveu um calendário. Confirma que é o URL do calendário do Moodle.")
                Settings.setSyncResult(c, false, r.message)
                return r
            }
            val added = merge(c, parse(text))
            Settings.setSyncResult(c, true, "")
            return Result(true, if (added > 0) "Moodle actualizado: $added novo${if (added == 1) "" else "s"}." else "Moodle actualizado.", added)
        } catch (e: Exception) {
            val r = Result(false, "Sem ligação ao Moodle. Volto a tentar mais tarde.")
            Settings.setSyncResult(c, false, r.message)
            return r
        } finally {
            running = false
        }
    }

    /** Junta o que veio do Moodle ao que já existe, sem perder o que ela marcou. Devolve quantos são novos. */
    fun merge(c: Context, events: List<Event>): Int {
        val turma = Settings.turma(c)
        val base = Settings.moodleBase(c)
        val mine = events.filter { !isOpening(it.summary) && !otherTurma(it.summary, turma) }
        val feed = mine.associateBy { "moodle:" + it.uid }
        var added = 0
        val nowMs = System.currentTimeMillis()
        // O Moodle só exporta os dias recentes e os próximos 60: fora disso, ausência não quer dizer nada
        val windowStart = nowMs - 5 * 86_400_000L
        val windowEnd = nowMs + 58 * 86_400_000L
        Store.update(c) { old ->
            val known = old.map { it.id }.toSet()
            val updated = old.map { d ->
                val e = feed[d.id]
                when {
                    e != null -> d.copy(
                        title = cleanTitle(e.summary),
                        course = cleanCourse(e.category),
                        dueMs = e.startMs,
                        description = cleanDescription(e.description),
                        link = firstLink(e.description).ifBlank { d.link },
                        goneFromMoodle = false
                    )
                    d.fromMoodle && d.doneAt == 0L && d.dueMs in windowStart..windowEnd -> d.copy(goneFromMoodle = true)
                    else -> d
                }
            }
            val fresh = feed.filterKeys { it !in known }.map { (id, e) ->
                added++
                Deadline(
                    id = id,
                    title = cleanTitle(e.summary),
                    course = cleanCourse(e.category),
                    dueMs = e.startMs,
                    description = cleanDescription(e.description),
                    link = firstLink(e.description),
                    fromMoodle = true
                )
            }
            updated + fresh
        }
        return added
    }

    /** Abre o dia do prazo no calendário do Moodle. */
    fun eventPage(c: Context, d: Deadline): String {
        val base = Settings.moodleBase(c)
        if (base.isBlank()) return ""
        return "$base/calendar/view.php?view=day&time=${d.dueMs / 1000}"
    }

    fun dashboard(c: Context): String {
        val base = Settings.moodleBase(c)
        return if (base.isBlank()) "" else "$base/my/"
    }
}
