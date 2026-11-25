package pt.ricardo.atempo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Reminders.channels(this)
        ReminderWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 && !granted(this, Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        incoming.value = intent
        setContent { AppTheme { App(incoming) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }
}

enum class Tab(val label: String) { HOJE("Hoje"), PRAZOS("Prazos"), HORARIO("Horário"), DEFINICOES("Definições") }

private val cheers = listOf(
    "Entregue! 🎨", "Mais um na parede da galeria 🖼️", "Feito, e a tempo 🦊", "Pincel pousado. Bem feito ✨",
    "Riscado da lista 🖌️", "Isso! Um a menos 🌷"
)

@Composable
private fun App(incoming: MutableState<Intent?>) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    var version by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    fun refresh() { version++ }
    fun toast(msg: String) { scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) } }

    fun syncNow(force: Boolean, quiet: Boolean) {
        if (syncing || Settings.icsUrl(c).isBlank()) return
        syncing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { if (force) Moodle.sync(c) else Moodle.syncIfStale(c, 30) }
            syncing = false
            refresh()
            if (r != null && (!quiet || !r.ok || r.added > 0)) toast(r.message)
        }
    }

    LifecycleResumeEffect(Unit) {
        Store.invalidate()
        refresh()
        syncNow(force = false, quiet = true)
        onPauseOrDispose { }
    }

    val all = remember(version) { Store.deadlines(c) }
    val slots = remember(version) { Store.slots(c) }

    var tab by rememberSaveable { mutableStateOf(Tab.HOJE) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }
    var confettiTint by remember { mutableStateOf<Color?>(null) }

    // Vindo de uma notificação
    LaunchedEffect(incoming.value) {
        val i = incoming.value ?: return@LaunchedEffect
        i.getStringExtra("abrir")?.let { openId = it; tab = Tab.PRAZOS }
        if (i.getStringExtra("aba") == "hoje") tab = Tab.HOJE
        incoming.value = null
    }

    fun done(id: String) {
        val d = Store.get(c, id)
        Store.edit(c, id) { it.copy(doneAt = System.currentTimeMillis()) }
        Reminders.cancel(c, id)
        confettiTint = d?.let { Palette.course(it.course) }
        confetti++
        toast(if (d?.isExam == true) "Teste feito! Agora descansa 📝" else cheers.random())
        refresh()
    }

    fun togglePlan(id: String) {
        Store.edit(c, id) { it.copy(planDay = if (it.plannedToday()) "" else today().toString()) }
        refresh()
    }

    val actions = DeadlineActions(onOpen = { openId = it }, onDone = ::done, onPlan = ::togglePlan)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = cs.background,
            snackbarHost = { SnackbarHost(snack) },
            floatingActionButton = {
                if (tab == Tab.HOJE || tab == Tab.PRAZOS) {
                    ExtendedFloatingActionButton(
                        onClick = { adding = true },
                        icon = { Icon(Icons.Filled.Add, null) },
                        text = { Text("Trabalho") },
                        containerColor = Palette.Coral,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            },
            bottomBar = {
                NavigationBar(containerColor = cs.surfaceContainerLow) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.HOJE -> Icons.Filled.Home
                                        Tab.PRAZOS -> Icons.Filled.List
                                        Tab.HORARIO -> Icons.Filled.DateRange
                                        Tab.DEFINICOES -> Icons.Filled.Settings
                                    }, null
                                )
                            },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = cs.primaryContainer)
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.HOJE -> TodayScreen(all, slots, actions, syncing, onGoSettings = { tab = Tab.DEFINICOES }, onSync = { syncNow(true, false) })
                    Tab.PRAZOS -> DeadlinesScreen(all, actions, onUndo = { id ->
                        Store.edit(c, id) { it.copy(doneAt = 0L, droppedAt = 0L) }; refresh()
                    })
                    Tab.HORARIO -> ScheduleScreen(slots, onChange = { refresh() })
                    Tab.DEFINICOES -> SettingsScreen(syncing, onSync = { syncNow(true, false) }, onChange = { refresh() })
                }
            }
        }
        Confetti(confetti, confettiTint)
    }

    openId?.let { id ->
        val d = all.firstOrNull { it.id == id }
        if (d == null) openId = null
        else DeadlineDialog(
            d,
            onDismiss = { openId = null },
            onDone = { openId = null; done(id) },
            onUndo = { Store.edit(c, id) { it.copy(doneAt = 0L, droppedAt = 0L) }; openId = null; refresh() },
            onPlan = { togglePlan(id) },
            onDrop = { Store.edit(c, id) { it.copy(droppedAt = System.currentTimeMillis()) }; openId = null; refresh(); toast("Tirado da lista.") },
            onDelete = { Store.remove(c, id); openId = null; refresh(); toast("Apagado.") }
        )
    }

    if (adding) {
        val courses = (all.map { it.course } + Palette.knownCourses()).filter { it.isNotBlank() }.distinct()
        AddDeadlineDialog(courses, onDismiss = { adding = false }) { d ->
            Store.add(c, d)
            adding = false
            refresh()
            toast("Guardado. Eu lembro-te 🦊")
        }
    }
}

data class DeadlineActions(val onOpen: (String) -> Unit, val onDone: (String) -> Unit, val onPlan: (String) -> Unit)
