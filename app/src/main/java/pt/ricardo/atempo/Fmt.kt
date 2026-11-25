package pt.ricardo.atempo

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Datas e contagens em português, escritas como uma pessoa as diria. */
object Fmt {
    val weekdays = listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo")
    val weekdaysShort = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
    private val months = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
    private val monthsLong = listOf(
        "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
        "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
    )

    fun dayShort(d: LocalDate) = "${weekdaysShort[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${months[d.monthValue - 1]}"

    fun dayLong(d: LocalDate) = "${weekdays[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }}, ${d.dayOfMonth} de ${monthsLong[d.monthValue - 1]}"

    fun time(z: ZonedDateTime) = "%02d:%02d".format(z.hour, z.minute)

    /** "sex 27 nov, 23:59", ou "até ao fim de qui 7 jan" quando o Moodle diz meia-noite. */
    fun due(d: Deadline): String =
        if (d.midnightTrap) "até ao fim de ${dayShort(d.effectiveDate)}"
        else "${dayShort(d.effectiveDate)}, ${time(d.effective)}"

    /** A contagem decrescente, sempre a partir do momento real do prazo. */
    fun countdown(d: Deadline, n: ZonedDateTime = now()): String {
        val mins = d.minutesLeft(n)
        if (mins < 0) {
            val days = ChronoUnit.DAYS.between(d.effectiveDate, n.toLocalDate())
            return when {
                days <= 0L -> "passou há ${-mins / 60} h"
                days == 1L -> "atrasado desde ontem"
                else -> "atrasado há $days dias"
            }
        }
        val days = d.daysLeft(n.toLocalDate())
        return when {
            days == 0L && mins < 60 -> "faltam $mins min"
            days == 0L -> "hoje · faltam ${mins / 60} h"
            days == 1L -> "amanhã"
            days < 7L -> "faltam $days dias"
            days < 14L -> "daqui a 1 semana"
            else -> "daqui a ${days / 7} semanas"
        }
    }

    fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
}

/** Janelas livres de um dia, a partir do horário. */
object FreeTime {
    data class Window(val start: Int, val end: Int) {
        val minutes get() = end - start
        override fun toString() = "${hm(start)}–${hm(end)}"
    }

    /** Entre as 9h e as 21h, buracos de pelo menos uma hora. */
    fun windows(slots: List<Slot>, day: LocalDate, from: Int = 9 * 60, to: Int = 21 * 60): List<Window> {
        val busy = slots.filter { it.day == day.dayOfWeek.value }.sortedBy { it.start }
        val out = mutableListOf<Window>()
        var cur = from
        for (s in busy) {
            if (s.start - cur >= 60) out += Window(cur, minOf(s.start, to))
            cur = maxOf(cur, s.end)
            if (cur >= to) break
        }
        if (to - cur >= 60) out += Window(cur, to)
        return out.filter { it.minutes >= 60 }
    }

    fun best(slots: List<Slot>, day: LocalDate, afterMinute: Int = 0): Window? =
        windows(slots, day).filter { it.end > afterMinute }.maxByOrNull { it.minutes }

    /** Algo fixo (como a Tuna) que acaba tarde nesse dia. */
    fun lateCommitment(slots: List<Slot>, day: LocalDate): Slot? =
        slots.filter { it.day == day.dayOfWeek.value && it.end >= 21 * 60 }.maxByOrNull { it.end }
}
