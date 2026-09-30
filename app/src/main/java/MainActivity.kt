package com.example.scibiditoilet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

enum class ScreenState { Loading, Native, Web, Error }
data class TeacherRef(val id: String, val name: String)

// iOS-палитра
object AppColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceElevated = Color(0xFF2C2C2E)
    val Divider = Color(0xFF38383A)
    val Primary = Color(0xFF0A84FF)
    val Accent = Color(0xFF30D158)
    val Warning = Color(0xFFFF9F0A)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8E8E93)
    val TextTertiary = Color(0xFF636366)
    val CardCorner = 20.dp
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() }

        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniqueWork("schedule_once", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints).build())
        wm.enqueueUniquePeriodicWork("schedule_watch", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(30, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
                .setConstraints(constraints).build())
    }
}

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun parseRange(time: String): Pair<LocalTime, LocalTime>? {
    val m = Regex("""(\d{1,2}:\d{2})\s*[–-]\s*(\d{1,2}:\d{2})""").find(time) ?: return null
    return try { Pair(LocalTime.parse(m.groupValues[1]), LocalTime.parse(m.groupValues[2])) } catch (_: Exception) { null }
}

private fun currentDayName(): String? = when (LocalDate.now().dayOfWeek) {
    DayOfWeek.MONDAY -> "Понедельник"; DayOfWeek.TUESDAY -> "Вторник"; DayOfWeek.WEDNESDAY -> "Среда"
    DayOfWeek.THURSDAY -> "Четверг"; DayOfWeek.FRIDAY -> "Пятница"; DayOfWeek.SATURDAY -> "Суббота"
    else -> null
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

// ─────────── Таймер пары (расписание) ───────────
@Composable
fun NowCard(days: List<Day>) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalTime.now() } }

    val dayName = currentDayName()
    val lessons = days.firstOrNull { it.name == dayName }?.lessons.orEmpty()
    val timed = lessons.mapNotNull { l -> parseRange(l.time)?.let { Triple(l, it.first, it.second) } }
    val current = timed.firstOrNull { !now.isBefore(it.second) && now.isBefore(it.third) }
    val next = timed.firstOrNull { now.isBefore(it.second) }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppColors.CardCorner))
            .background(Brush.verticalGradient(listOf(AppColors.SurfaceElevated, AppColors.Surface)))
            .padding(20.dp)
    ) {
        when {
            current != null -> {
                val (l, start, end) = current
                val minsLeft = ChronoUnit.MINUTES.between(now, end)
                val total = ChronoUnit.MINUTES.between(start, end).coerceAtLeast(1)
                val elapsed = ChronoUnit.MINUTES.between(start, now).coerceAtLeast(0)
                Text("СЕЙЧАС ИДЁТ", color = AppColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Spacer(Modifier.height(6.dp))
                Text(l.title, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Конец через $minsLeft мин · ${l.time}", color = AppColors.TextSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppColors.Divider)) {
                    Box(
                        Modifier.fillMaxWidth((elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            .height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(listOf(AppColors.Accent, Color(0xFF48D97E))))
                    )
                }
            }
            next != null -> {
                val (l, start, _) = next
                Text("СЛЕДУЮЩАЯ ПАРА", color = AppColors.Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                Spacer(Modifier.height(6.dp))
                Text(l.title, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Начало через ${ChronoUnit.MINUTES.between(now, start)} мин · $start", color = AppColors.TextSecondary, fontSize = 15.sp)
            }
            dayName == null -> {
                Text("ВОСКРЕСЕНЬЕ", color = AppColors.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("Выходной 🎉", color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            else -> {
                Text("НА СЕГОДНЯ", color = AppColors.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("Пар больше нет", color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────── Карточка замен ───────────
@Composable
fun SubstitutionsCard(text: String) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppColors.CardCorner))
            .background(Color(0xFF2A1B05))
            .border(1.dp, AppColors.Warning.copy(alpha = 0.6f), RoundedCornerShape(AppColors.CardCorner))
            .padding(20.dp)
    ) {
        Text("⚠️ ЗАМЕНЫ", color = AppColors.Warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = AppColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
    }
}

// ─────────── Таймер звонков ───────────
private data class Interval(val start: LocalTime, val end: LocalTime, val kind: String, val num: String)

private fun buildIntervals(rows: List<BellRow>): List<Interval> {
    val list = mutableListOf<Interval>()
    val parsed = rows.mapNotNull { r -> parseRange(r.time)?.let { Pair(r, it) } }
    parsed.forEachIndexed { i, (r, range) ->
        list.add(Interval(range.first, range.second, "lesson", r.number))
        val lunch = parseRange(r.note.substringAfter("обед", ""))
        if (lunch != null) list.add(Interval(lunch.first, lunch.second, "lunch", ""))
        if (i < parsed.size - 1) {
            val nextStart = parsed[i + 1].second.first
            if (nextStart.isAfter(range.second)) list.add(Interval(range.second, nextStart, "break", parsed[i + 1].first.number))
        }
    }
    return list.sortedBy { it.start }
}

@Composable
fun BellsTimerCard(rows: List<BellRow>) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val intervals = buildIntervals(rows)
            val cur = intervals.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
            val minsEnd = cur?.let { ChronoUnit.MINUTES.between(now, it.end) } ?: 5
            delay(if (minsEnd <= 2) 30_000 else 60_000)
            now = LocalTime.now()
        }
    }

    val intervals = remember(rows) { buildIntervals(rows) }
    val cur = intervals.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
    val next = intervals.firstOrNull { now.isBefore(it.start) }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppColors.CardCorner))
            .background(Brush.verticalGradient(listOf(AppColors.SurfaceElevated, AppColors.Surface)))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    cur != null -> {
                        val label = when (cur.kind) {
                            "lesson" -> "ИДЁТ ${cur.num} ПАРА"
                            "lunch" -> "ИДЁТ ОБЕД"
                            else -> "ИДЁТ ПЕРЕРЫВ"
                        }
                        val color = when (cur.kind) {
                            "lesson" -> AppColors.Accent; "lunch" -> AppColors.Warning; else -> AppColors.Primary
                        }
                        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                        Spacer(Modifier.height(6.dp))
                        val mins = ChronoUnit.MINUTES.between(now, cur.end)
                        Text(
                            if (mins <= 0) "Конец с минуты на минуту" else "Конец через $mins мин",
                            color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                        )
                    }
                    next != null -> {
                        Text("СКОРО", color = AppColors.Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                        Spacer(Modifier.height(6.dp))
                        val what = when (next.kind) {
                            "lesson" -> "${next.num} пара"; "lunch" -> "обед"; else -> "пара"
                        }
                        Text("$what через ${ChronoUnit.MINUTES.between(now, next.start)} мин",
                            color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    else -> {
                        Text("ЗАНЯТИЯ", color = AppColors.TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("На сегодня всё", color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(now.format(TIME_FMT), color = AppColors.TextSecondary, style = MaterialTheme.typography.titleLarge)
        }
    }
}

// ─────────── Шеринг ───────────
fun shareLesson(context: Context, lesson: Lesson, dayName: String) {
    val text = buildString {
        appendLine("📚 ${lesson.title}")
        if (lesson.teacher.isNotEmpty()) appendLine("👤 ${lesson.teacher}")
        appendLine("🕐 $dayName, ${lesson.number} пара · ${lesson.time}")
        appendLine()
        append("— Расписание 25уКСК-1")
    }
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
        "Поделиться парой"))
}

// ─────────── iOS-навигационный бар ───────────
@Composable
fun IosNavigationBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(AppColors.Surface.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Вкладка "Расписание"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onTabSelected(0) }
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = if (selectedTab == 0) AppColors.Primary else AppColors.TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Расписание",
                    color = if (selectedTab == 0) AppColors.Primary else AppColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
            // Вкладка "Звонки"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onTabSelected(1) }
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = if (selectedTab == 1) AppColors.Primary else AppColors.TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Звонки",
                    color = if (selectedTab == 1) AppColors.Primary else AppColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─────────── Экраны ───────────
@Composable
fun App() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = AppColors.Background, surface = AppColors.Surface,
        onBackground = AppColors.TextPrimary, onSurface = AppColors.TextPrimary, primary = AppColors.Primary
    )) {
        var tab by remember { mutableStateOf(0) }
        var teacher by remember { mutableStateOf<TeacherRef?>(null) }

        Scaffold(
            containerColor = AppColors.Background,
            bottomBar = {
                if (teacher == null) {
                    IosNavigationBar(selectedTab = tab, onTabSelected = { tab = it })
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).statusBarsPadding().background(AppColors.Background)) {
                val t = teacher
                if (t != null) TeacherScreen(t) { teacher = null }
                else if (tab == 0) ScheduleScreen { teacher = it } else BellsScreen()
            }
        }
    }
}

@Composable
fun TeacherScreen(ref: TeacherRef, onBack: () -> Unit) {
    var days by remember { mutableStateOf<List<Day>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var notFound by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    LaunchedEffect(ref.id) {
        scope.launch {
            var id = ref.id
            if (id.isEmpty()) id = ScheduleParser.fetchTeacherIdByName(ref.name) ?: ""
            if (id.isEmpty()) notFound = true
            else days = ScheduleParser.fetchTeacherSchedule(id)
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(AppColors.Background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AppColors.TextPrimary) }
            Text(ref.name, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Загрузка…", color = AppColors.TextSecondary)
            }
            notFound || days.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Расписание не найдено", color = AppColors.TextSecondary)
            }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                days.forEachIndexed { index, day ->
                    item(key = "tday_${day.name}") { DayHeader(day.name, isFirst = index == 0) }
                    items(items = day.lessons, key = { "${day.name}_${it.number}_${it.time}" }) { l ->
                        LessonCard(l, day.name, currentWeek = "", onTeacherClick = null)
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleScreen(onTeacherClick: (TeacherRef) -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ScreenState.Loading) }
    var days by remember { mutableStateOf<List<Day>>(emptyList()) }
    var subs by remember { mutableStateOf("") }
    var week by remember { mutableStateOf("") }
    var lastUpdate by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun load(forceRefresh: Boolean = false) {
        scope.launch {
            val meta = ScheduleCache.loadMeta(context)
            subs = meta.first; week = meta.second
            lastUpdate = ScheduleCache.getLastUpdateTime(context)

            val cached = ScheduleCache.loadDays(context)
            if (!cached.isNullOrEmpty() && !forceRefresh) { days = cached; state = ScreenState.Native }

            if (isNetworkAvailable(context)) {
                val parsed = ScheduleParser.fetchSchedule()
                if (parsed.isNotEmpty()) {
                    ScheduleCache.setDays(parsed); ScheduleCache.saveDays(context, parsed)
                    days = parsed; state = ScreenState.Native
                    lastUpdate = System.currentTimeMillis()
                } else if (cached.isNullOrEmpty()) state = ScreenState.Web

                val m = ScheduleParser.fetchMeta()
                if (m.first.isNotEmpty() || m.second.isNotEmpty()) {
                    ScheduleCache.saveMeta(context, m.first, m.second)
                    subs = m.first; week = m.second
                }
            } else if (cached.isNullOrEmpty()) {
                state = if (File(context.filesDir, "schedule_offline.html").exists()) ScreenState.Web else ScreenState.Error
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Box(Modifier.fillMaxSize()) {
        when (state) {
            ScreenState.Loading -> Column(Modifier.fillMaxSize().background(AppColors.Background)) {}
            ScreenState.Native -> NativeSchedule(days, subs, week, { load(true) }, onTeacherClick, lastUpdate)
            ScreenState.Web -> FallbackWebView(ScheduleParser.SCHEDULE_URL, "schedule_offline.html", buildCleanJs(true, isNetworkAvailable(context)))
            ScreenState.Error -> ErrorScreen { load() }
        }
    }
}

@Composable
fun BellsScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ScreenState.Loading) }
    var groups by remember { mutableStateOf<List<BellGroup>>(emptyList()) }
    var selectedCourse by remember { mutableStateOf("2 курс") }
    val scope = rememberCoroutineScope()

    fun load(forceRefresh: Boolean = false) {
        scope.launch {
            val cached = ScheduleCache.loadBells(context)
            if (!cached.isNullOrEmpty()) { groups = cached; state = ScreenState.Native }
            if (isNetworkAvailable(context)) {
                val parsed = ScheduleParser.fetchBells()
                if (parsed.isNotEmpty()) {
                    ScheduleCache.setBells(parsed); ScheduleCache.saveBells(context, parsed)
                    groups = parsed; state = ScreenState.Native
                } else if (cached.isNullOrEmpty()) state = ScreenState.Web
            } else if (cached.isNullOrEmpty()) {
                state = if (File(context.filesDir, "bells_offline.html").exists()) ScreenState.Web else ScreenState.Error
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Box(Modifier.fillMaxSize()) {
        when (state) {
            ScreenState.Loading -> Column(Modifier.fillMaxSize().background(AppColors.Background)) {}
            ScreenState.Native -> {
                val isSaturday = LocalDate.now().dayOfWeek == DayOfWeek.SATURDAY
                val courses = groups.filter { it.title.endsWith("курс") }
                val activeGroup = if (isSaturday) groups.firstOrNull { it.title == "Суббота" }
                else groups.firstOrNull { it.title == selectedCourse } ?: courses.firstOrNull()

                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Звонки", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { load(true) }) { Icon(Icons.Default.Refresh, "Обновить", tint = AppColors.Primary) }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (!isSaturday && courses.isNotEmpty()) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                courses.forEach { g ->
                                    val selected = g.title == selectedCourse
                                    val bgColor by animateColorAsState(if (selected) AppColors.Primary else AppColors.Surface)
                                    val txtColor by animateColorAsState(if (selected) Color.White else AppColors.TextSecondary)
                                    Text(
                                        g.title,
                                        color = txtColor,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(bgColor)
                                            .clickable { selectedCourse = g.title }
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (activeGroup != null) {
                        item { BellsTimerCard(activeGroup.rows) }
                        item {
                            Column(Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(AppColors.CardCorner))
                                .background(AppColors.Surface)
                                .padding(20.dp)) {
                                Text(activeGroup.title, color = AppColors.Primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(16.dp))
                                activeGroup.rows.forEachIndexed { index, r ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(r.number, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
                                        Text(r.time, style = MaterialTheme.typography.bodyLarge)
                                        if (r.note.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text(r.note, color = AppColors.Warning) }
                                    }
                                    if (index < activeGroup.rows.size - 1) HorizontalDivider(color = AppColors.Divider, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }
            ScreenState.Web -> FallbackWebView(ScheduleParser.BELLS_URL, "bells_offline.html", DARK_JS)
            ScreenState.Error -> ErrorScreen { load() }
        }
    }
}

@Composable
fun NativeSchedule(days: List<Day>, subs: String, week: String, onRefresh: () -> Unit,
                   onTeacherClick: (TeacherRef) -> Unit, lastUpdate: Long) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        item {
            Column(Modifier.padding(bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Расписание", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("25уКСК-1", color = AppColors.Primary, style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить", tint = AppColors.Primary) }
                }
                if (lastUpdate > 0) {
                    val minutes = ChronoUnit.MINUTES.between(
                        java.time.Instant.ofEpochMilli(lastUpdate),
                        java.time.Instant.now()
                    )
                    Text("обновлено $minutes мин назад", color = AppColors.TextTertiary, fontSize = 12.sp)
                }
            }
        }
        if (subs.isNotEmpty()) item { SubstitutionsCard(subs); Spacer(Modifier.height(12.dp)) }
        item { NowCard(days); Spacer(Modifier.height(8.dp)) }
        days.forEachIndexed { index, day ->
            item(key = "day_${day.name}") { DayHeader(day.name, isFirst = index == 0) }
            items(items = day.lessons, key = { "${day.name}_${it.number}_${it.time}" }) { l ->
                LessonCard(l, day.name, week, onTeacherClick)
            }
        }
    }
}

@Composable
fun DayHeader(name: String, isFirst: Boolean) {
    Column(Modifier.padding(top = if (isFirst) 0.dp else 20.dp)) {
        if (!isFirst) HorizontalDivider(color = AppColors.Divider, thickness = 0.5.dp, modifier = Modifier.padding(bottom = 20.dp))
        Text(name.uppercase(), color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp), letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LessonCard(l: Lesson, dayName: String, currentWeek: String, onTeacherClick: ((TeacherRef) -> Unit)?) {
    val context = LocalContext.current
    val roomText = if (l.teacher.isNotEmpty()) l.meta.replace(l.teacher, "").trim(' ', '·', ',', '-', '\n') else ""

    val isActual = currentWeek.isNotEmpty() && l.weekType == currentWeek
    val isOther = currentWeek.isNotEmpty() && l.weekType.isNotEmpty() && l.weekType != currentWeek
    val cardAlpha by animateFloatAsState(if (isOther) 0.55f else 1f)
    val titleSize = if (isActual) 20.sp else 17.sp
    val fontWeight = if (isActual) FontWeight.Bold else FontWeight.Medium

    val bgBrush = if (isActual) {
        Brush.verticalGradient(listOf(Color(0xFF1E3A5F), Color(0xFF16243A)))
    } else {
        Brush.verticalGradient(listOf(AppColors.SurfaceElevated, AppColors.Surface))
    }

    Column(
        Modifier.fillMaxWidth()
            .alpha(cardAlpha)
            .shadow(8.dp, RoundedCornerShape(AppColors.CardCorner))
            .clip(RoundedCornerShape(AppColors.CardCorner))
            .background(bgBrush)
            .border(1.dp, if (isActual) AppColors.Primary.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(AppColors.CardCorner))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isActual) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(60.dp)
                        .background(AppColors.Primary, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (l.number.isNotEmpty()) {
                        Text("${l.number} пара", color = AppColors.TextTertiary, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        HorizontalDivider(color = AppColors.Divider, modifier = Modifier.height(1.dp).weight(1f))
                    }
                    Text(l.time, color = AppColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    l.title,
                    color = AppColors.TextPrimary,
                    fontSize = titleSize,
                    fontWeight = fontWeight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                if (l.teacher.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("👤 ", fontSize = 16.sp)
                        Text(
                            "${l.teacher} →",
                            color = AppColors.Primary,
                            modifier = if (onTeacherClick != null) {
                                Modifier
                                    .clickable { onTeacherClick(TeacherRef(l.teacherId, l.teacher)) }
                                    .padding(vertical = 2.dp)
                            } else Modifier,
                            fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline
                        )
                        if (roomText.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text("· $roomText", color = AppColors.TextSecondary, fontSize = 14.sp)
                        }
                    }
                } else if (l.meta.isNotEmpty()) {
                    Text(l.meta, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AppColors.Divider, thickness = 0.5.dp)
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (l.weekType.isNotEmpty()) {
                        val weekLabel = when (l.weekType) {
                            "odd" -> "Нечётная"
                            "even" -> "Чётная"
                            else -> l.weekType
                        }
                        Text(
                            weekLabel,
                            color = if (isActual) AppColors.Accent else AppColors.TextTertiary,
                            fontSize = 12.sp
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    if (onTeacherClick != null) {
                        TextButton(onClick = { shareLesson(context, l, dayName) }) {
                            Icon(Icons.Default.Share, null, tint = AppColors.Primary, modifier = Modifier.width(18.dp).height(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Поделиться", color = AppColors.Primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorScreen(retry: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AppColors.Background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("📡", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text("Нет подключения", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Сохранённая копия отсутствует", color = AppColors.TextSecondary)
        Spacer(Modifier.height(32.dp))
        Button(onClick = retry, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary), shape = RoundedCornerShape(20.dp)) {
            Icon(Icons.Default.Refresh, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Повторить", color = Color.White)
        }
    }
}

@Composable
fun FallbackWebView(url: String, offlineFile: String, js: String) {
    var ref by remember { mutableStateOf<WebView?>(null) }
    BackHandler { if (ref?.canGoBack() == true) ref?.goBack() }
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            ref = this
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; loadWithOverviewMode = true
                useWideViewPort = true; builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    super.onPageFinished(view, u)
                    view?.evaluateJavascript(js, null)
                    view?.postDelayed({ view.evaluateJavascript(js, null) }, 1500)
                    if (isNetworkAvailable(ctx)) {
                        view?.postDelayed({
                            view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                val decoded: String? = try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { null }
                                if (!decoded.isNullOrBlank()) {
                                    if (offlineFile.contains("schedule")) ScheduleCache.scheduleHtml = decoded else ScheduleCache.bellsHtml = decoded
                                    runCatching { File(ctx.filesDir, offlineFile).writeText(decoded) }
                                }
                            }
                        }, 4000)
                    }
                }
            }
            val memCache = if (offlineFile.contains("schedule")) ScheduleCache.scheduleHtml else ScheduleCache.bellsHtml
            val diskFile = File(ctx.filesDir, offlineFile)
            when {
                !memCache.isNullOrBlank() -> loadDataWithBaseURL(url, memCache, "text/html; charset=utf-8", "utf-8", null)
                isNetworkAvailable(ctx) -> loadUrl(url)
                diskFile.exists() -> loadDataWithBaseURL(url, diskFile.readText(), "text/html; charset=utf-8", "utf-8", null)
                else -> loadUrl(url)
            }
        }
    })
}

val DARK_JS = """
    (function(){
        if (!document.getElementById('dark-theme-style')) {
            var st = document.createElement('style');
            st.id = 'dark-theme-style';
            st.textContent = 'html{filter:invert(1) hue-rotate(180deg);background:#000!important}' +
                             'img,video,svg,iframe{filter:invert(1) hue-rotate(180deg)}';
            document.documentElement.appendChild(st);
        }
    })();
"""

fun buildCleanJs(isMainPage: Boolean, online: Boolean): String {
    return """
        (function() {
            try {
                if (!window.__cleanupDefined) {
                    window.__cleanupDefined = true;
                    if (!document.getElementById('dark-theme-style')) {
                        var st = document.createElement('style');
                        st.id = 'dark-theme-style';
                        st.textContent = 'html{filter:invert(1) hue-rotate(180deg);background:#000!important}' +
                                         'img,video,svg,iframe{filter:invert(1) hue-rotate(180deg)}';
                        document.documentElement.appendChild(st);
                    }
                    window.hasSchedule = function(el) {
                        if (!el || !el.querySelector) return false;
                        if (el.querySelector('table')) return true;
                        var t = el.innerText || '';
                        return t.indexOf('Ближайшие замены') !== -1 || t.indexOf('Понедельник') !== -1 ||
                               t.indexOf('нечётная') !== -1 || t.indexOf('учебная неделя') !== -1;
                    };
                    function collapseEmptyUp(el, levels, maxH) {
                        var p = el;
                        for (var i = 0; i < levels; i++) {
                            p = p ? p.parentElement : null;
                            if (!p || p === document.body) break;
                            var t = (p.innerText || '').trim();
                            if (t === '' && !window.hasSchedule(p) && p.offsetHeight < maxH) p.style.display = 'none';
                            else break;
                        }
                    }
                    window.applyCleanup = function() {
                        var nodes = document.querySelectorAll('a,button,input,span,div');
                        for (var n = 0; n < nodes.length; n++) {
                            var node = nodes[n];
                            if (node.style && node.style.display === 'none') continue;
                            var txt = (node.textContent || '').trim();
                            var h = node.offsetHeight;
                            if (node.tagName === 'INPUT' && h < 150) { node.style.display = 'none'; collapseEmptyUp(node, 2, 300); continue; }
                            if ((txt === 'По группам' || txt === 'По преподавателям' || txt === 'По кабинетам') && node.children.length <= 1 && h < 150) { node.style.display = 'none'; collapseEmptyUp(node, 2, 300); continue; }
                            if (txt.indexOf('Расписание звонков') !== -1 && node.children.length <= 2 && h < 150) { node.style.display = 'none'; collapseEmptyUp(node, 2, 300); continue; }
                            if (/^\d+[А-Яа-яA-Za-zЁё]+-\d+$/.test(txt) && node.children.length <= 1 && h > 0 && h < 150) { node.style.display = 'none'; collapseEmptyUp(node, 1, 600); }
                        }
                    };
                }
                window.applyCleanup();
                if (!window.__observerSet) {
                    window.__observerSet = true;
                    var last = 0;
                    var mo = new MutationObserver(function() {
                        var now = Date.now();
                        if (now - last > 500) { last = now; window.applyCleanup(); }
                    });
                    mo.observe(document.body, { childList: true, subtree: true });
                    setTimeout(function() { mo.disconnect(); }, 20000);
                }
            } catch (e) {}
        })();
    """.trimIndent()
}