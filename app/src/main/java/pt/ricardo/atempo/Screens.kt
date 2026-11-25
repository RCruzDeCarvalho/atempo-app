package pt.ricardo.atempo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant

private val screenPad = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 110.dp)

// =====================================================================
// HOJE
// =====================================================================

@Composable
fun TodayScreen(
    all: List<Deadline>,
    slots: List<Slot>,
    actions: DeadlineActions,
    syncing: Boolean,
    onGoSettings: () -> Unit,
    onSync: () -> Unit
) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val n = now()
    val t = n.toLocalDate()
    val active = all.filter { it.active }.sortedBy { it.dueMs }
    val late = active.filter { it.isLate(n) }
    val planned = active.filter { !it.isLate(n) && it.plannedToday() }
    val exams = active.filter { it.isExam && !it.plannedToday() && it.daysLeft(t) <= 21 }
    val week = active.filter { !it.isExam && !it.isLate(n) && !it.plannedToday() && it.daysLeft(t) <= 7 }
    val leftovers = active.filter { !it.isExam && it.leftover() && !it.plannedToday() && it.daysLeft(t) > 7 }
    val examToday = active.firstOrNull { it.isExam && it.daysLeft(t) == 0L }
    val friday = n.dayOfWeek == DayOfWeek.FRIDAY
    val todaySlots = slots.filter { it.day == n.dayOfWeek.value }
    val minuteNow = n.hour * 60 + n.minute
    val windows = FreeTime.windows(slots, t).filter { it.end > minuteNow }

    LazyColumn(contentPadding = screenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Hero(Settings.name(c), t, late.size, week.size + planned.size, active.firstOrNull { !it.isLate(n) && !it.isExam }, examToday) }

        if (Settings.icsUrl(c).isBlank()) item {
            NoteCard(
                "🔗 Falta ligar o Moodle",
                "Cola o link do calendário do Moodle nas Definições e os trabalhos aparecem aqui sozinhos.",
                "Ir às Definições", onGoSettings
            )
        } else if (Settings.syncError(c).isNotBlank()) item {
            NoteCard("☁️ Moodle em pausa", Settings.syncError(c), if (syncing) "A tentar…" else "Tentar agora", onSync)
        }

        if (friday) item { FridayCard(onMoodle = { openUrl(c, Moodle.dashboard(c)) }, hasMoodle = Settings.icsUrl(c).isNotBlank()) }

        if (late.isNotEmpty()) {
            item { SectionTitle("Em atraso", Palette.Late, "Ainda dá para entregar? Muitas vezes o Moodle aceita, marcado como atrasado.") }
            items(late, key = { it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }
        }

        if (planned.isNotEmpty()) {
            item { SectionTitle("Hoje faço ✨", hint = "Uma coisa de cada vez.") }
            items(planned, key = { it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }
        }

        if (leftovers.isNotEmpty()) {
            item { SectionTitle("Ficou da última vez", hint = "Sem drama. Escolhe se é para hoje.") }
            items(leftovers, key = { "l" + it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }
        }

        if (exams.isNotEmpty()) {
            item { SectionTitle("Testes e exames 📝", hint = "Nas próximas 3 semanas. Estudar um bocadinho por dia rende mais.") }
            items(exams, key = { "e" + it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }
        }

        item { SectionTitle("Próximos 7 dias", hint = if (week.isEmpty()) "Nada com prazo esta semana. Respira 🌿" else null) }
        items(week, key = { "w" + it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }

        item { SectionTitle(if (friday) "Hoje está tudo livre" else "Hoje no horário") }
        if (todaySlots.isEmpty()) item {
            Text("Sem aulas hoje.", color = cs.onSurfaceVariant)
        } else items(todaySlots, key = { "s" + it.id }) { SlotRow(it, past = it.end <= minuteNow) }
        if (windows.isNotEmpty()) item {
            Spacer(Modifier.height(6.dp))
            Text("Janelas livres para trabalhar", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(windows) { w -> WindowChip("${hm(maxOf(w.start, minuteNow))}–${hm(w.end)}") }
            }
        }
    }
}

@Composable
private fun Hero(name: String, t: java.time.LocalDate, late: Int, soon: Int, next: Deadline?, examToday: Deadline?) {
    val line = when {
        examToday != null -> "Hoje tens ${examToday.title}, às ${Fmt.time(examToday.effective)}. Tu consegues 💪"
        late > 0 -> "Tens ${Fmt.plural(late, "trabalho em atraso", "trabalhos em atraso")}. Vamos tratar disso primeiro."
        next != null && next.daysLeft() == 0L -> "Hoje entregas «${next.title}»."
        soon > 0 -> "Esta semana: ${Fmt.plural(soon, "entrega", "entregas")}. Vais a tempo."
        else -> "Nada urgente. Bom dia para criar com calma."
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Palette.hero)
            .padding(22.dp)
    ) {
        Column {
            Text(Fmt.dayLong(t), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.height(4.dp))
            Text("Olá, $name!", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(line, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
}

@Composable
private fun FridayCard(onMoodle: () -> Unit, hasMoodle: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.friday)
            .padding(18.dp)
    ) {
        Column {
            Text("🎨 Sexta é dia de trabalho", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                "Escolhe 2 ou 3 trabalhos e carrega em «Faço hoje». Ao fim do dia marcas o que ficou entregue. E espreita as páginas «Datas e Deadlines» do Moodle, que nem tudo aparece no calendário.",
                color = Color.White, style = MaterialTheme.typography.bodyMedium
            )
            if (hasMoodle) {
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = onMoodle) { Text("Abrir o Moodle") }
            }
        }
    }
}

@Composable
private fun NoteCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun WindowChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, Palette.Ok.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) { Text("🌿 $text", color = Palette.Ok, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SlotRow(s: Slot, past: Boolean = false, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val col = if (s.isClass) Palette.course(s.title) else Palette.Ochre
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerLowest.copy(alpha = if (past) 0.5f else 1f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp)
    ) {
        Column(Modifier.width(56.dp)) {
            Text(hm(s.start), fontWeight = FontWeight.SemiBold, color = if (past) cs.onSurfaceVariant else cs.onSurface)
            Text(hm(s.end), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Box(Modifier.width(5.dp).height(36.dp).clip(RoundedCornerShape(3.dp)).background(col.copy(alpha = if (past) 0.4f else 1f)))
        Spacer(Modifier.width(12.dp))
        Text(
            (if (s.isClass) "" else "🎻 ") + s.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (past) cs.onSurfaceVariant else cs.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

// =====================================================================
// PRAZOS
// =====================================================================

@Composable
fun DeadlinesScreen(all: List<Deadline>, actions: DeadlineActions, onUndo: (String) -> Unit) {
    val n = now()
    val active = all.filter { it.active }.sortedBy { it.dueMs }
    val late = active.filter { it.isLate(n) }
    val exams = active.filter { it.isExam }
    val week = active.filter { !it.isExam && !it.isLate(n) && it.daysLeft() <= 7 }
    val later = active.filter { !it.isExam && it.daysLeft() > 7 }
    val finished = all.filter { !it.active && !it.goneFromMoodle }.sortedByDescending { it.finishedAt() }
    var showDone by rememberSaveable { mutableStateOf(false) }

    LazyColumn(contentPadding = screenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Prazos", style = MaterialTheme.typography.displaySmall)
            Text("Do Moodle e os que acrescentaste à mão.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (active.isEmpty()) item {
            SectionTitle("Nada pendente 🌿", hint = "Quando o Moodle tiver entregas, aparecem aqui.")
        }
        if (late.isNotEmpty()) {
            item { SectionTitle("Em atraso", Palette.Late) }
            items(late, key = { it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }
        }
        if (exams.isNotEmpty()) {
            item { SectionTitle("Testes e exames 📝") }
            items(exams, key = { it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }
        }
        if (week.isNotEmpty()) {
            item { SectionTitle("Esta semana") }
            items(week, key = { it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }
        }
        if (later.isNotEmpty()) {
            item { SectionTitle("Mais à frente") }
            items(later, key = { it.id }) { DeadlineCard(it, { actions.onOpen(it.id) }, { actions.onDone(it.id) }, { actions.onPlan(it.id) }) }
        }
        if (finished.isNotEmpty()) {
            item {
                TextButton(onClick = { showDone = !showDone }) {
                    Text((if (showDone) "Esconder" else "Ver") + " o que já passou (${finished.size})")
                }
            }
            if (showDone) items(finished, key = { "f" + it.id }) { d -> FinishedRow(d, onOpen = { actions.onOpen(d.id) }, onUndo = { onUndo(d.id) }) }
        }
    }
}

@Composable
private fun FinishedRow(d: Deadline, onOpen: () -> Unit, onUndo: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.course(d.course)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val whenTxt = Fmt.dayShort(Instant.ofEpochMilli(d.finishedAt()).atZone(zone).toLocalDate())
            val ok = d.doneAt > 0 || (d.examOver && d.droppedAt == 0L)
            Text(
                when {
                    d.isExam && ok -> "📝 feito a "
                    d.doneAt > 0 -> "✓ entregue a "
                    else -> "tirado da lista a "
                } + whenTxt,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) Palette.Ok else cs.onSurfaceVariant
            )
        }
        if (!d.examOver) TextButton(onClick = onUndo) { Text("Desfazer") }
    }
}

// =====================================================================
// HORÁRIO
// =====================================================================

@Composable
fun ScheduleScreen(slots: List<Slot>, onChange: () -> Unit) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var day by rememberSaveable { mutableIntStateOf(today().dayOfWeek.value) }
    var editing by remember { mutableStateOf<Slot?>(null) }
    var adding by remember { mutableStateOf(false) }
    val list = slots.filter { it.day == day }.sortedBy { it.start }

    // Usa a próxima ocorrência deste dia da semana para calcular as janelas livres
    val sample = today().let { t -> t.plusDays(((day - t.dayOfWeek.value + 7) % 7).toLong()) }
    val windows = FreeTime.windows(slots, sample)

    LazyColumn(contentPadding = screenPad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Horário", style = MaterialTheme.typography.displaySmall)
            Text("Turma ${Settings.turma(c)} · toca numa aula para a mudar.", color = cs.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items((1..7).toList()) { i ->
                    FilterChip(selected = day == i, onClick = { day = i }, label = { Text(Fmt.weekdaysShort[i - 1].replaceFirstChar { it.uppercase() }) })
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (list.isEmpty()) item {
            Text(if (day == 5) "Sexta sem aulas. É o dia de trabalho 🎨" else "Nada neste dia.", color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        }
        items(list, key = { it.id }) { s -> SlotRow(s, onClick = { editing = s }) }
        if (windows.isNotEmpty() && list.isNotEmpty()) item {
            Spacer(Modifier.height(6.dp))
            Text("Livre: " + windows.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Palette.Ok)
        }
        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { adding = true }) { Text("+ Acrescentar a ${Fmt.weekdays[day - 1]}") }
        }
    }

    if (adding || editing != null) {
        val initial = editing
        SlotDialog(
            initial = initial,
            defaultDay = day,
            onDismiss = { adding = false; editing = null },
            onSave = { s ->
                Store.updateSlots(c) { l -> if (initial == null) l + s else l.map { if (it.id == s.id) s else it } }
                adding = false; editing = null; onChange()
            },
            onDelete = if (initial != null) ({
                Store.updateSlots(c) { l -> l.filterNot { it.id == initial.id } }
                editing = null; onChange()
            }) else null
        )
    }
}

// =====================================================================
// DEFINIÇÕES
// =====================================================================

@Composable
fun SettingsScreen(syncing: Boolean, onSync: () -> Unit, onChange: () -> Unit) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var url by remember { mutableStateOf(Settings.icsUrl(c)) }
    var name by remember { mutableStateOf(Settings.name(c)) }
    var turma by remember { mutableStateOf(Settings.turma(c)) }
    var morning by remember { mutableIntStateOf(Settings.morningHour(c)) }
    var friday by remember { mutableIntStateOf(Settings.fridayHour(c)) }
    var tick by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text("Definições", style = MaterialTheme.typography.displaySmall)

        SectionTitle("Moodle")
        Text(
            "No Moodle (no computador): Calendário → Importar ou exportar calendários → Exportar calendário. Escolhe «Todos os eventos» e «Dias recentes e próximos 60», carrega em «Obter URL do calendário» e cola aqui o link.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(url, { url = it }, label = { Text("Link do calendário") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = !syncing && url.isNotBlank(), onClick = {
                Settings.setIcsUrl(c, url); onSync(); tick++
            }) { Text(if (syncing) "A actualizar…" else "Guardar e actualizar") }
        }
        Spacer(Modifier.height(6.dp))
        key(tick, syncing) {
            val last = Settings.lastSync(c)
            val err = Settings.syncError(c)
            Text(
                when {
                    err.isNotBlank() -> "⚠️ $err"
                    last > 0 -> "Última actualização: ${Fmt.dayShort(Instant.ofEpochMilli(last).atZone(zone).toLocalDate())}, ${Fmt.time(Instant.ofEpochMilli(last).atZone(zone))}. Volta a ver sozinha de 3 em 3 horas."
                    else -> "Ainda não foi ao Moodle."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (err.isNotBlank()) cs.error else cs.onSurfaceVariant
            )
        }

        SectionTitle("Eu")
        OutlinedTextField(name, { name = it; Settings.setName(c, it); onChange() }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(turma, { v ->
            turma = v.uppercase().take(1); Settings.setTurma(c, turma)
        }, label = { Text("Turma (só mostra os trabalhos desta letra)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        SectionTitle("Lembretes")
        HourStepper("Resumo da manhã", morning, 6, 12) { morning = it; Settings.setMorningHour(c, it) }
        Spacer(Modifier.height(6.dp))
        HourStepper("Plano de sexta-feira", friday, 7, 13) { friday = it; Settings.setFridayHour(c, it) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Cada trabalho avisa uma semana antes, dois dias antes ao fim da tarde, e no próprio dia seis, três e uma hora antes. Os testes e exames avisam duas semanas, uma semana e três dias antes, na véspera ao fim da tarde e duas horas antes. À sexta há o plano de manhã, um toque às 15h e o fecho às 19h.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { Reminders.test(c) }) { Text("Experimentar uma notificação") }

        Spacer(Modifier.height(28.dp))
        Text("A Tempo ${BuildConfig.VERSION_NAME} · feita pelo pai, com carinho 🦊", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(100.dp))
    }
}

/** Quando saiu da lista: entregue, tirado, ou (num teste) o próprio dia do teste. */
private fun Deadline.finishedAt(): Long = maxOf(doneAt, droppedAt).takeIf { it > 0 } ?: dueMs
