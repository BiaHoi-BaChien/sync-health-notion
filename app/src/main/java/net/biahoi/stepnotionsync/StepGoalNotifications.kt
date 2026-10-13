package net.biahoi.stepnotionsync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

internal const val STEP_GOAL_KEY = "dailyStepGoal"
internal const val STEP_GOAL_NOTIFICATION_KEY = "stepGoalNotificationsEnabled"
internal const val DEFAULT_STEP_GOAL = 10_000

internal fun parseStepGoal(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it > 0 }

internal data class StepGoalSettings(val goal: Int, val enabled: Boolean) {
    companion object {
        fun load(context: Context): StepGoalSettings {
            val prefs = context.getSharedPreferences("notion", Context.MODE_PRIVATE)
            return StepGoalSettings(
                prefs.getInt(STEP_GOAL_KEY, DEFAULT_STEP_GOAL).takeIf { it > 0 } ?: DEFAULT_STEP_GOAL,
                prefs.getBoolean(STEP_GOAL_NOTIFICATION_KEY, false),
            )
        }
    }
}

internal data class StepGoalNotice(val date: String, val goal: Int, val milestone: Int)

internal data class StepGoalProgress(val date: LocalDate, val steps: Long, val goal: Int) {
    init {
        require(steps >= 0 && goal > 0)
    }

    val percent: Long get() = (steps.toDouble() * 100 / goal).toLong()
    val milestone: Int get() = listOf(100, 75, 50, 25).firstOrNull {
        steps >= (goal.toLong() * it + 99) / 100
    } ?: 0

    fun nextNotice(previous: StepGoalNotice): StepGoalNotice? {
        val alreadyNotified = if (previous.date == date.toString() && previous.goal == goal) previous.milestone else 0
        return milestone.takeIf { it > alreadyNotified }?.let { StepGoalNotice(date.toString(), goal, it) }
    }

    fun summary(): String = String.format(Locale.JAPAN, "%,d / %,d歩（達成率 %,d％）", steps, goal, percent)
}

internal fun stepGoalDayStart(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

internal object StepGoalNotifications {
    const val CHANNEL_ID = "step_goal_milestones"
    private const val WORK_NAME = "step_goal_notifications"
    private const val NOTIFICATION_ID = 2501
    private val checkMutex = Mutex()

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "歩数目標の達成通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "1日の歩数目標の25％・50％・75％・100％達成をお知らせします。"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun supportsBackgroundRead(client: HealthConnectClient): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (StepGoalSettings.load(context).enabled) {
            ensureChannel(context)
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StepGoalWorker>(15, TimeUnit.MINUTES).build(),
            )
        } else {
            workManager.cancelUniqueWork(WORK_NAME)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    suspend fun check(context: Context, inBackground: Boolean): String = checkMutex.withLock {
        val settings = StepGoalSettings.load(context)
        if (!settings.enabled) return@withLock "歩数の達成通知はOFFです。"
        ensureChannel(context)
        if (!canNotify(context)) return@withLock "Androidの通知を許可してください。"
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return@withLock "Health Connectが利用できません。"
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted) {
            return@withLock "Health Connectの歩数の読み取りを許可してください。"
        }
        val backgroundAllowed = supportsBackgroundRead(client) &&
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
        if (inBackground && !backgroundAllowed) return@withLock "バックグラウンドでの歩数の読み取りが許可されていません。"

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val start = stepGoalDayStart(now, zone)
        val steps = if (start == now) 0L else withContext(Dispatchers.IO) {
            client.aggregate(AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, now),
            ))[StepsRecord.COUNT_TOTAL] ?: 0L
        }
        val progress = StepGoalProgress(now.atZone(zone).toLocalDate(), steps, settings.goal)
        // Serialize posting with settings saves, and recheck after the suspended Health Connect read.
        withContext(Dispatchers.Main.immediate) {
            if (StepGoalSettings.load(context) != settings || !canNotify(context) ||
                ZoneId.systemDefault() != zone || LocalDate.now(zone) != progress.date
            ) return@withContext
            val history = context.getSharedPreferences("step_goal_notifications", Context.MODE_PRIVATE)
            val previous = StepGoalNotice(
                history.getString("date", "").orEmpty(), history.getInt("goal", 0), history.getInt("milestone", 0),
            )
            if (previous.date != progress.date.toString() || previous.goal != settings.goal) {
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
            progress.nextNotice(previous)?.let { notice ->
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val notification = Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_footsteps)
                    .setContentTitle("歩数目標を${notice.milestone}％達成")
                    .setContentText(progress.summary())
                    .setStyle(Notification.BigTextStyle().bigText("${progress.date}\n${progress.summary()}"))
                    .setProgress(settings.goal, steps.coerceAtMost(settings.goal.toLong()).toInt(), false)
                    .setContentIntent(PendingIntent.getActivity(
                        context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ))
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                history.edit().putString("date", notice.date).putInt("goal", notice.goal)
                    .putInt("milestone", notice.milestone).apply()
            }
        }
        "今日: ${progress.summary()}" + if (backgroundAllowed) "" else
            "\nアプリ表示時のみ確認します。定期通知にはバックグラウンド読み取りの対応と許可が必要です。"
    }
}

class StepGoalWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        StepGoalNotifications.check(applicationContext, inBackground = true)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: SecurityException) {
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
