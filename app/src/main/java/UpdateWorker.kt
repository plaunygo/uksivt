package com.example.scibiditoilet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.Calendar

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // Окна высокой активности: 18-22 включительно и 6-7 включительно
        val isHighActivity = hour in 18..22 || hour in 6..7
        val isEvenHour = hour % 2 == 0

        // Выполняем только в нужное время: либо окно высокой активности (каждые 30 мин),
        // либо чётный час (каждые 2 часа)
        if (!isHighActivity && !isEvenHour) {
            return@withContext Result.success()
        }

        try {
            val doc = Jsoup.connect(ScheduleParser.SCHEDULE_URL).timeout(15000).get()

            // Расписание
            val parsed = ScheduleParser.parseSchedule(doc)
            if (parsed.isNotEmpty()) {
                ScheduleCache.setDays(parsed)
                ScheduleCache.saveDays(applicationContext, parsed)
            }

            // Мета: замены и неделя
            val subs = ScheduleParser.extractSubstitutions(doc)
            val week = ScheduleParser.extractWeekType(doc)
            if (subs.isNotEmpty() || week.isNotEmpty()) {
                ScheduleCache.saveMeta(applicationContext, subs, week)
            }

            // Звонки
            val bellsDoc = Jsoup.connect(ScheduleParser.BELLS_URL).timeout(15000).get()
            val bellsParsed = ScheduleParser.parseBells(bellsDoc)
            if (bellsParsed.isNotEmpty()) {
                ScheduleCache.setBells(bellsParsed)
                ScheduleCache.saveBells(applicationContext, bellsParsed)
            }

            // Уведомление при изменении замен
            val prefs = applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)
            val newHash = subs.hashCode().toString()
            val oldHash = prefs.getString("sub_hash", null)
            prefs.edit().putString("sub_hash", newHash).apply()
            if (subs.isNotEmpty() && oldHash != null && oldHash != newHash) {
                notifyUser(subs)
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notifyUser(text: String) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("schedule_watch", "Изменения расписания", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.notify(101, NotificationCompat.Builder(applicationContext, "schedule_watch")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Расписание изменилось")
            .setContentText("Появились новые замены")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).build())
    }
}