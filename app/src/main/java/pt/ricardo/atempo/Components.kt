package pt.ricardo.atempo

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

fun urgencyColor(d: Deadline): Color {
    if (d.isLate()) return Palette.Late
    val days = d.daysLeft()
    return when {
        days <= 1 -> Palette.Coral
        days <= 3 -> Palette.Ochre
        else -> Palette.course(d.course)
    }
}

/** Um anel que se vai fechando à medida que o prazo chega (duas semanas = anel cheio). */
@Composable
fun CountdownRing(d: Deadline, size: Int = 54) {
    val cs = MaterialTheme.colorScheme
    val days = d.daysLeft()
    val late = d.isLate()
    val color = urgencyColor(d)
    val frac = when {
        late -> 1f
        else -> (days.toFloat() / 14f).coerceIn(0.04f, 1f)
    }
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.dp)) {
            val sw = 5.dp.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            drawArc(cs.outlineVariant, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                late -> Text("!", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color)
                days == 0L -> Text("hoje", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = color)
                else -> {
                    Text("$days", fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 17.sp, color = cs.onSurface)
                    Text(if (days == 1L) "dia" else "dias", fontSize = 10.sp, lineHeight = 10.sp, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun CourseTag(course: String) {
    if (course.isBlank()) return
    val col = Palette.course(course)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(6.dp))
        Text(course, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DeadlineCard(
    d: Deadline,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onPlan: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val late = d.isLate()
    val bg = if (late) Palette.LateSoft.copy(alpha = if (cs.background.luminanceIsDark()) 0.15f else 1f) else cs.surfaceContainerLowest
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(7.dp).fillMaxHeight().background(Palette.course(d.course)))
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        if (d.isExam) {
                            Text("📝 ${d.kindLabel.uppercase()}", style = MaterialTheme.typography.labelMedium, color = Palette.Coral, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        CourseTag(d.course)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            Fmt.due(d).replaceFirstChar { it.uppercase() } + " · " + Fmt.countdown(d),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (late) Palette.Late else cs.onSurfaceVariant,
                            fontWeight = if (late || d.daysLeft() <= 1) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (d.leftover()) {
                            Spacer(Modifier.height(4.dp))
                            Text("↺ ficou da última vez", style = MaterialTheme.typography.labelMedium, color = Palette.Coral)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    CountdownRing(d)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!d.isExam) FilledTonalButton(onClick = onDone, shape = RoundedCornerShape(14.dp)) { Text("✓ Entregue") }
                    val verb = if (d.isExam) "Estudo hoje" else "Faço hoje"
                    FilterChip(
                        selected = d.plannedToday(),
                        onClick = onPlan,
                        label = { Text(if (d.plannedToday()) "$verb ✨" else verb) }
                    )
                    Spacer(Modifier.weight(1f))
                    if (!d.fromMoodle) Text("à mão", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

private fun Color.luminanceIsDark() = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

@Composable
fun SectionTitle(text: String, color: Color = MaterialTheme.colorScheme.onBackground, hint: String? = null) {
    Column(Modifier.padding(top = 18.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = color)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun openUrl(c: android.content.Context, url: String) {
    if (url.isBlank()) return
    try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

// ---------- detalhe de um trabalho ----------

@Composable
fun DeadlineDialog(
    d: Deadline,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onUndo: () -> Unit,
    onPlan: () -> Unit,
    onDrop: () -> Unit,
    onDelete: () -> Unit
) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CourseTag(d.course)
                Spacer(Modifier.height(10.dp))
                Text(Fmt.due(d).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                if (d.active) Text(Fmt.countdown(d), color = urgencyColor(d), fontWeight = FontWeight.SemiBold)
                if (d.midnightTrap) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "O Moodle marca este prazo para a meia-noite de ${Fmt.dayShort(d.due.toLocalDate())}. Na prática acaba na noite anterior, por isso conta com ${Fmt.dayShort(d.effectiveDate)} como último dia.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.Coral
                    )
                }
                if (d.doneAt > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text((if (d.isExam) "✓ Feito a " else "✓ Entregue a ") + "${Fmt.dayShort(Instant.ofEpochMilli(d.doneAt).atZone(zone).toLocalDate())}", color = Palette.Ok, fontWeight = FontWeight.SemiBold)
                }
                if (d.description.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(d.description, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.link.isNotBlank()) OutlinedButton(onClick = { openUrl(c, d.link) }) { Text("Enunciado") }
                    if (d.fromMoodle) OutlinedButton(onClick = { openUrl(c, Moodle.eventPage(c, d)) }) { Text("Ver no Moodle") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.active) TextButton(onClick = onPlan) { Text(if (d.plannedToday()) "Hoje não" else if (d.isExam) "Estudo hoje" else "Faço hoje") }
                    if (d.active && d.fromMoodle) TextButton(onClick = onDrop) { Text("Não é para mim", color = cs.onSurfaceVariant) }
                    if (!d.fromMoodle) TextButton(onClick = onDelete) { Text("Apagar", color = cs.error) }
                }
            }
        },
        confirmButton = {
            if (d.active) FilledTonalButton(onClick = onDone) { Text(if (d.isExam) "✓ Já fiz" else "✓ Entregue") }
            else if (!d.examOver) TextButton(onClick = onUndo) { Text("Voltar a pôr na lista") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

// ---------- acrescentar um trabalho à mão ----------

@Composable
fun AddDeadlineDialog(courses: List<String>, onDismiss: () -> Unit, onSave: (Deadline) -> Unit) {
    val c = LocalContext.current
    val slots = remember { Store.slots(c) }
    var kind by remember { mutableStateOf(KIND_WORK) }
    var place by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today().plusDays(7)) }
    var minute by remember { mutableIntStateOf(23 * 60 + 59) }
    var desc by remember { mutableStateOf("") }
    var timeTouched by remember { mutableStateOf(false) }
    val exam = kind != KIND_WORK

    // Num teste, a hora por defeito é a da aula dessa cadeira nesse dia
    fun guessTime() {
        if (!exam || timeTouched) return
        val s = slots.firstOrNull { it.day == date.dayOfWeek.value && course.isNotBlank() && it.title.startsWith(course, true) }
        minute = s?.start ?: (10 * 60)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (exam) "Novo ${if (kind == KIND_TEST) "teste" else "exame"}" else "Novo trabalho") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(KIND_WORK to "Trabalho", KIND_TEST to "Teste", KIND_EXAM to "Exame").forEach { (k, l) ->
                        FilterChip(selected = kind == k, onClick = {
                            kind = k
                            if (k == KIND_WORK) { if (!timeTouched) minute = 23 * 60 + 59 } else guessTime()
                        }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (exam) "Os avisos começam duas semanas antes e lembram-te de ir estudando."
                    else "Para o que foi dito na aula ou está numa página do Moodle sem data no calendário.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(course, { course = it; guessTime() }, label = { Text("Cadeira") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(courses) { k ->
                        SuggestionChip(onClick = { course = k; guessTime() }, label = { Text(k, maxLines = 1) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    title, { title = it },
                    label = { Text(if (exam) "Nome (opcional)" else "O que é") },
                    placeholder = { if (exam && course.isNotBlank()) Text("${if (kind == KIND_TEST) "Teste" else "Exame"} de $course") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (exam) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(place, { place = it }, label = { Text("Sala (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        DatePickerDialog(c, { _, y, m, dd -> date = LocalDate.of(y, m + 1, dd); guessTime() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    }) { Text("📅 " + Fmt.dayShort(date)) }
                    OutlinedButton(onClick = {
                        TimePickerDialog(c, { _, h, mi -> minute = h * 60 + mi; timeTouched = true }, minute / 60, minute % 60, true).show()
                    }) { Text("🕒 " + hm(minute)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(desc, { desc = it }, label = { Text("Notas (opcional)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
            }
        },
        confirmButton = {
            FilledTonalButton(enabled = title.isNotBlank() || (exam && course.isNotBlank()), onClick = {
                val due = date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
                val name = title.trim().ifBlank { "${if (kind == KIND_TEST) "Teste" else "Exame"} de ${course.trim()}" }
                onSave(Deadline(title = name, course = course.trim(), dueMs = due, description = desc.trim(), kind = kind, place = place.trim()))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ---------- aulas do horário ----------

@Composable
fun SlotDialog(initial: Slot?, defaultDay: Int, onDismiss: () -> Unit, onSave: (Slot) -> Unit, onDelete: (() -> Unit)?) {
    val c = LocalContext.current
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var day by remember { mutableIntStateOf(initial?.day ?: defaultDay) }
    var start by remember { mutableIntStateOf(initial?.start ?: 10 * 60) }
    var end by remember { mutableIntStateOf(initial?.end ?: 12 * 60) }
    var isClass by remember { mutableStateOf(initial?.isClass ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nova no horário" else "Editar") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((1..7).toList()) { i ->
                        FilterChip(selected = day == i, onClick = { day = i }, label = { Text(Fmt.weekdaysShort[i - 1]) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { TimePickerDialog(c, { _, h, m -> start = h * 60 + m }, start / 60, start % 60, true).show() }) { Text("Início " + hm(start)) }
                    OutlinedButton(onClick = { TimePickerDialog(c, { _, h, m -> end = h * 60 + m }, end / 60, end % 60, true).show() }) { Text("Fim " + hm(end)) }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isClass, onCheckedChange = { isClass = it })
                    Spacer(Modifier.width(10.dp))
                    Text(if (isClass) "É uma aula" else "Outra coisa fixa (Tuna, etc.)")
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onDelete) { Text("Apagar do horário", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(enabled = title.isNotBlank() && end > start, onClick = {
                onSave((initial ?: Slot(day = day, start = start, end = end, title = title)).copy(day = day, start = start, end = end, title = title.trim(), isClass = isClass))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun HourStepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { if (value > min) onChange(value - 1) }, shape = CircleShape) { Text("−") }
        Text("%02d:00".format(value), modifier = Modifier.padding(horizontal = 10.dp), fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { if (value < max) onChange(value + 1) }, shape = CircleShape) { Text("+") }
    }
}
