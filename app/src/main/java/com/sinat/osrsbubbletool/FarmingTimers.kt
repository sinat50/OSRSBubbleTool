package com.sinat.osrsbubbletool

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

// Farming and birdhouse timers: the saved timers, the growth times, and the alarms.
//
// Nothing runs while a timer is waiting. Android wakes the app once, when a timer is due,
// to show the notification. Timers are saved, so they survive closing the app, and
// FarmingTimerReceiver sets the alarms again after the phone restarts.
//
// Growth times follow RuneLite's Time Tracking plugin (github.com/runelite/runelite; Copyright (c) 2018-2019, Abex,
// Copyright (c) 2018, NotFoxtrot; BSD 2-Clause License, full text on the app's Legal screen): crops grow one stage on each
// "growth tick", and growth ticks happen at fixed times on the clock (every 5, 10, 20,
// 40... minutes), not counted from when you planted.
object FarmingTimers {
    const val ACTION_DONE = "com.sinat.osrsbubbletool.FARMING_TIMER_DONE"
    const val EXTRA_ID = "timer_id"
    private const val CHANNEL_ID = "farming_timers"
    private const val PREFS = "farming_timers"
    private const val NOTIFICATION_BASE = 1000   // notification ids 1000+ (the bubble uses 1)

    // A crop: minutes per growth stage, and number of stages (as RuneLite counts them)
    class Crop(val name: String, val stageMinutes: Int, val stages: Int)

    // A kind of timer. fixedMinutes > 0 means a plain countdown (birdhouses).
    class Kind(val key: String, val label: String, val crops: List<Crop>, val fixedMinutes: Int = 0) {
        fun readyTitle(crop: Crop): String =
            if (crops.size > 1) "$label: ${crop.name} ready" else "$label ready"
    }

    private fun single(key: String, label: String, stageMinutes: Int, stages: Int) =
        Kind(key, label, listOf(Crop(label, stageMinutes, stages)))

    val KINDS: List<Kind> = listOf(
        single("herbs", "Herb run", 20, 5),
        Kind("birdhouses", "Birdhouse run", listOf(Crop("Birdhouses", 0, 0)), fixedMinutes = 50),
        Kind("trees", "Tree run", listOf(
            Crop("Oak", 40, 5), Crop("Willow", 40, 7), Crop("Maple", 40, 9),
            Crop("Yew", 40, 11), Crop("Magic", 40, 13))),
        single("fruit_trees", "Fruit tree run", 160, 7),
        Kind("allotments", "Allotments", listOf(
            Crop("Potato", 10, 5), Crop("Onion", 10, 5), Crop("Cabbage", 10, 5),
            Crop("Tomato", 10, 5), Crop("Sweetcorn", 10, 7), Crop("Strawberry", 10, 7),
            Crop("Watermelon", 10, 9), Crop("Snape grass", 10, 8))),
        single("flowers", "Flowers", 5, 5),
        Kind("hops", "Hops", listOf(
            Crop("Barley", 10, 5), Crop("Hammerstone", 10, 5), Crop("Asgarnian", 10, 6),
            Crop("Jute", 10, 6), Crop("Yanillian", 10, 7), Crop("Krandorian", 10, 8),
            Crop("Wildblood", 10, 9), Crop("Flax", 20, 4), Crop("Hemp", 20, 5), Crop("Cotton", 20, 6))),
        Kind("bushes", "Bushes", listOf(
            Crop("Redberry", 20, 6), Crop("Cadavaberry", 20, 7), Crop("Dwellberry", 20, 8),
            Crop("Jangerberry", 20, 9), Crop("Whiteberry", 20, 9), Crop("Poison ivy", 20, 9))),
        Kind("hardwood", "Hardwood trees", listOf(
            Crop("Teak", 640, 8), Crop("Mahogany", 640, 9), Crop("Camphor", 640, 9),
            Crop("Ironwood", 640, 9), Crop("Rosewood", 640, 10))),
        single("seaweed", "Seaweed", 10, 5),
        single("calquat", "Calquat", 160, 9),
        single("celastrus", "Celastrus", 160, 6),
        single("redwood", "Redwood", 640, 11),
        single("spirit_tree", "Spirit tree", 320, 13),
        single("crystal_tree", "Crystal tree", 80, 7),
        Kind("cactus", "Cactus", listOf(Crop("Cactus", 80, 8), Crop("Potato cactus", 10, 8))),
        single("mushroom", "Mushroom", 40, 7),
        single("belladonna", "Belladonna", 80, 5),
        single("grapes", "Grapes", 5, 8),
        single("hespori", "Hespori", 640, 4),
        Kind("anima", "Anima", listOf(Crop("Attas", 640, 9), Crop("Iasor", 640, 9), Crop("Kronos", 640, 9))),
        single("coral", "Coral", 40, 5),
        single("compost", "Compost bin", 40, 3)
    )

    fun kind(key: String): Kind = KINDS.firstOrNull { it.key == key } ?: KINDS[0]

    // One timer the player has on their list. end = 0 means not running.
    class Timer(val id: Int, val kind: String, var crop: Int, var end: Long, var notified: Boolean) {
        fun kindInfo() = kind(kind)
        fun cropInfo(): Crop = kindInfo().crops.let { it[crop.coerceIn(0, it.size - 1)] }
    }

    // ---------------- Saving ----------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): MutableList<Timer> {
        val text = prefs(context).getString("timers", null)
            ?: return mutableListOf(   // first time: a few common runs
                Timer(1, "herbs", 0, 0, false),
                Timer(2, "birdhouses", 0, 0, false),
                Timer(3, "trees", 4, 0, false),
                Timer(4, "fruit_trees", 0, 0, false)
            ).also { save(context, it) }
        // read each timer on its own, so one damaged entry can't lose (or crash on) the rest
        val array = try { JSONArray(text) } catch (e: Exception) { return mutableListOf() }
        val list = mutableListOf<Timer>()
        for (i in 0 until array.length()) {
            try {
                val o = array.getJSONObject(i)
                list.add(Timer(o.getInt("id"), o.getString("kind"), o.optInt("crop"), o.optLong("end"), o.optBoolean("notified")))
            } catch (e: Exception) { }
        }
        return list
    }

    @Synchronized
    fun save(context: Context, timers: List<Timer>) {
        val array = JSONArray()
        for (t in timers) array.put(JSONObject().apply {
            put("id", t.id); put("kind", t.kind); put("crop", t.crop)
            put("end", t.end); put("notified", t.notified)
        })
        prefs(context).edit { putString("timers", array.toString()) }
    }

    // Some accounts have their growth ticks shifted by a few minutes. RuneLite measures this;
    // here the player can set it by hand if timers are always early or late.
    fun tickOffset(context: Context) = prefs(context).getInt("tick_offset", 0)
    fun setTickOffset(context: Context, minutes: Int) =
        prefs(context).edit { putInt("tick_offset", minutes) }

    // ---------------- Growth math ----------------

    // When a crop planted at `now` (ms) will be fully grown
    fun finishTime(context: Context, kind: Kind, crop: Crop, now: Long): Long {
        if (kind.fixedMinutes > 0) return now + kind.fixedMinutes * 60_000L
        val rate = crop.stageMinutes * 60L
        val offset = (tickOffset(context) % crop.stageMinutes) * 60L
        val t = now / 1000 + offset
        val tickStart = t - t % rate   // the growth tick the crop was planted in
        return (tickStart + (crop.stages - 1) * rate - offset) * 1000
    }

    // ---------------- Starting and stopping ----------------

    fun start(context: Context, id: Int) {
        val timers = load(context)
        val t = timers.firstOrNull { it.id == id } ?: return
        t.end = finishTime(context, t.kindInfo(), t.cropInfo(), System.currentTimeMillis())
        t.notified = false
        save(context, timers)
        cancelNotification(context, t.id)
        schedule(context, t)
    }

    fun stop(context: Context, id: Int) {
        val timers = load(context)
        val t = timers.firstOrNull { it.id == id } ?: return
        t.end = 0
        t.notified = false
        save(context, timers)
        cancelAlarm(context, id)
        cancelNotification(context, id)
    }

    fun remove(context: Context, id: Int) {
        stop(context, id)
        val timers = load(context)
        timers.removeAll { it.id == id }
        save(context, timers)
    }

    fun add(context: Context, kindKey: String) {
        val timers = load(context)
        val id = (timers.maxOfOrNull { it.id } ?: 0) + 1
        timers.add(Timer(id, kindKey, 0, 0, false))
        save(context, timers)
    }

    fun setCrop(context: Context, id: Int, crop: Int) {
        val timers = load(context)
        val t = timers.firstOrNull { it.id == id } ?: return
        t.crop = crop
        save(context, timers)
        if (t.end > 0) start(context, id)   // changing the crop of a running timer restarts it
    }

    // ---------------- Alarms ----------------

    fun canUseExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun alarmIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, FarmingTimerReceiver::class.java).apply {
            action = ACTION_DONE
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(context: Context, t: Timer) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, t.id)
        try {
            if (canUseExactAlarms(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.end, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.end, pi)   // may be a few minutes late
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.end, pi)
        }
    }

    private fun cancelAlarm(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))
    }

    // After a restart (alarms are wiped) or an app update: set every alarm again,
    // and show anything that finished while the phone was off.
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (t in load(context)) {
            if (t.end <= 0 || t.notified) continue
            if (t.end <= now) onAlarm(context, t.id) else schedule(context, t)
        }
    }

    // Called by FarmingTimerReceiver when an alarm goes off
    fun onAlarm(context: Context, id: Int) {
        val timers = load(context)
        val t = timers.firstOrNull { it.id == id } ?: return
        if (t.end <= 0 || t.notified) return
        if (t.end > System.currentTimeMillis() + 60_000) {   // woke up too early: try again
            schedule(context, t)
            return
        }
        t.notified = true
        save(context, timers)
        showNotification(context, t)
    }

    // ---------------- Notifications ----------------

    fun notificationsAllowed(context: Context) =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun showNotification(context: Context, t: Timer) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Farming timers", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(t.kindInfo().readyTitle(t.cropInfo()))
            .setContentText("Ready to harvest")
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
        try {
            nm.notify(NOTIFICATION_BASE + t.id, notification)
        } catch (e: SecurityException) {
            // notifications are turned off for the app; the tool shows a button to fix that
        }
    }

    private fun cancelNotification(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_BASE + id)
    }
}
