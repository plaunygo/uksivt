package com.example.scibiditoilet

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class Lesson(
    val number: String,
    val time: String,
    val title: String,
    val meta: String,
    val teacher: String = "",
    val teacherId: String = "",
    val weekType: String = ""
)
data class Day(val name: String, val lessons: List<Lesson>)
data class BellRow(val number: String, val time: String, val note: String)
data class BellGroup(val title: String, val rows: List<BellRow>)

object ScheduleParser {
    private const val TAG = "ScheduleParser"
    const val SCHEDULE_URL = "https://schedule.uksivt.ru/public?mode=group&id=43"
    const val BELLS_URL = "https://schedule.uksivt.ru/public/bells"

    private val DAYS = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота")
    private val RANGE = Regex("""\d{1,2}:\d{2}\s*[–-]\s*\d{1,2}:\d{2}""")
    private val NUM = Regex("""^\d{1,2}$""")

    suspend fun fetchSchedule(): List<Day> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(SCHEDULE_URL).timeout(15000).get()
            parseSchedule(doc)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSchedule error", e)
            emptyList()
        }
    }

    suspend fun fetchMeta(): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(SCHEDULE_URL).timeout(15000).get()
            Pair(extractSubstitutions(doc), extractWeekType(doc))
        } catch (e: Exception) {
            Log.e(TAG, "fetchMeta error", e)
            Pair("", "")
        }
    }

    suspend fun fetchTeacherIdByName(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect("https://schedule.uksivt.ru/public?mode=teacher").timeout(15000).get()
            val links = doc.select("a[href*=teacher]")
            val exact = links.firstOrNull { it.text().trim() == name }
            val fuzzy = exact ?: links.firstOrNull { a ->
                val t = a.text().trim()
                t.isNotEmpty() && (t.contains(name) || name.contains(t))
            }
            // Берём href и извлекаем id из query параметра
            fuzzy?.attr("abs:href")?.let { href ->
                Regex("""[?&]id=(\d+)""").find(href)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTeacherIdByName error", e)
            null
        }
    }

    suspend fun fetchTeacherSchedule(teacherId: String): List<Day> = withContext(Dispatchers.IO) {
        try {
            parseSchedule(Jsoup.connect("https://schedule.uksivt.ru/public?mode=teacher&id=$teacherId").timeout(15000).get())
        } catch (e: Exception) {
            Log.e(TAG, "fetchTeacherSchedule error", e)
            emptyList()
        }
    }

    suspend fun fetchBells(): List<BellGroup> = withContext(Dispatchers.IO) {
        try {
            parseBells(Jsoup.connect(BELLS_URL).timeout(15000).get())
        } catch (e: Exception) {
            Log.e(TAG, "fetchBells error", e)
            emptyList()
        }
    }

    fun extractWeekType(doc: Document): String {
        val t = doc.text()
        return when {
            t.contains("нечётная") || t.contains("нечетная") -> "odd"
            t.contains("чётная") || t.contains("четная") -> "even"
            else -> ""
        }
    }

    // ─── Парсинг расписания ───
    fun parseSchedule(doc: Document): List<Day> {
        val days = mutableListOf<Day>()
        val dayNames = DAYS.toSet()

        val headerCandidates = doc.select("h1,h2,h3,h4,h5,div,span,b,strong").filter { el ->
            val text = el.text().trim()
            text in dayNames && el.children().size <= 1
        }

        for (header in headerCandidates) {
            var container: Element? = header.parent()
            var maxLevels = 6
            var foundContainer: Element? = null
            while (container != null && maxLevels > 0) {
                if (container.select("*").any { RANGE.containsMatchIn(it.text()) && it !== container }) {
                    foundContainer = container
                    break
                }
                container = container.parent()
                maxLevels--
            }
            if (foundContainer == null) continue

            val lessonBlocks = foundContainer.select("*").filter { leaf ->
                leaf.children().isEmpty() && RANGE.containsMatchIn(leaf.text())
            }.mapNotNull { leaf ->
                var pairBlock: Element? = leaf.parent()
                var steps = 0
                while (pairBlock != null && steps < 4) {
                    val txt = pairBlock.text()
                    if (RANGE.containsMatchIn(txt) && txt.length > 10) break
                    pairBlock = pairBlock.parent()
                    steps++
                }
                pairBlock
            }.distinct()

            val lessons = lessonBlocks.mapNotNull { block -> parseLessonFromBlock(block) }
                .filter { it.title.isNotBlank() || it.time.isNotBlank() }

            if (lessons.isNotEmpty()) {
                days.add(Day(header.text().trim(), lessons))
            }
        }
        return days
    }

    private fun parseLessonFromBlock(block: Element): Lesson? {
        val time = RANGE.find(block.text())?.value?.replace(" ", "") ?: return null
        val leaves = block.select("*").filter { it.children().isEmpty() }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it != "сейчас" }
        val number = leaves.firstOrNull { NUM.matches(it) } ?: ""
        val rest = leaves.filter { it != number && !RANGE.containsMatchIn(it) }

        val teacherLink = block.select("a[href*=teacher]").firstOrNull()
        val teacher = teacherLink?.text()?.trim() ?: ""
        val teacherId = teacherLink?.let { link ->
            val href = link.attr("abs:href")
            Regex("""[?&]id=(\d+)""").find(href)?.groupValues?.get(1) ?: ""
        } ?: ""

        val badge = block.select("*").firstOrNull { el ->
            el.children().isEmpty() && el.text().length < 12 &&
                    (el.text().contains("нечёт") || el.text().contains("нечет") ||
                            el.text().contains("чёт") || el.text().contains("чет"))
        }
        val weekType = badge?.let { b ->
            when {
                b.text().contains("нечёт") || b.text().contains("нечет") -> "odd"
                b.text().contains("чёт") || b.text().contains("чет") -> "even"
                else -> ""
            }
        } ?: ""

        return Lesson(
            number = number,
            time = time,
            title = rest.firstOrNull() ?: "",
            meta = rest.drop(1).joinToString(" · "),
            teacher = teacher,
            teacherId = teacherId,
            weekType = weekType
        )
    }

    // ─── Парсинг звонков ───
    fun parseBells(doc: Document): List<BellGroup> {
        val groups = mutableListOf<BellGroup>()
        val headers = doc.select("h1,h2,h3,h4,h5,div,span,b,strong").filter { el ->
            val t = el.text().trim()
            (t.matches(Regex("""^\d\s*курс$""")) || t == "Суббота") && el.children().isEmpty()
        }

        for (h in headers) {
            var container = h.parent()
            var maxLevels = 5
            var foundContainer: Element? = null
            while (container != null && maxLevels > 0) {
                if (container.select("*").any { RANGE.containsMatchIn(it.text()) && it !== container }) {
                    foundContainer = container
                    break
                }
                container = container.parent()
                maxLevels--
            }
            if (foundContainer == null) continue

            val bellCandidates = foundContainer.select("*").filter { leaf ->
                leaf.children().isEmpty() && RANGE.containsMatchIn(leaf.text())
            }.mapNotNull { leaf ->
                var row: Element? = leaf.parent()
                var steps = 0
                while (row != null && steps < 3) {
                    if (row.select("*").any { it.children().isEmpty() && NUM.matches(it.text()) }) break
                    row = row.parent()
                    steps++
                }
                row
            }.distinct()

            val rows = bellCandidates.mapNotNull { block -> parseBellRowFromBlock(block) }
                .filter { it.time.isNotBlank() }

            if (rows.isNotEmpty()) {
                groups.add(BellGroup(h.text().trim(), rows))
            }
        }
        return groups
    }

    private fun parseBellRowFromBlock(block: Element): BellRow? {
        val leaves = block.select("*").filter { it.children().isEmpty() }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it != "сейчас" }
        val number = leaves.firstOrNull { NUM.matches(it) } ?: return null
        val time = leaves.firstOrNull { RANGE.containsMatchIn(it) } ?: return null
        val note = leaves.firstOrNull { it.startsWith("обед", ignoreCase = true) } ?: ""
        return BellRow(number, time, note)
    }

    // ─── Извлечение замен ───
    fun extractSubstitutions(doc: Document): String {
        val header = doc.select("h2,h3,h4,h5,div,span,b,strong")
            .firstOrNull { it.text().trim().startsWith("Ближайшие замены") } ?: return ""

        var container = header.parent()
        for (i in 0..3) {
            if (container != null && container.text().length > header.text().length + 10) break
            container = container?.parent()
        }
        if (container == null) return ""

        val fullText = container.text()
        val headerText = header.text()
        val startIndex = fullText.indexOf(headerText)
        if (startIndex == -1) return ""
        val result = fullText.substring(startIndex + headerText.length).trim()

        val nextHeader = container.select("h1,h2,h3,h4,h5").firstOrNull { it.text() != headerText && it.text().isNotBlank() }
        if (nextHeader != null) {
            val nextHeaderText = nextHeader.text()
            val endIndex = result.indexOf(nextHeaderText)
            if (endIndex != -1) return result.substring(0, endIndex).trim()
        }
        return result
    }
}