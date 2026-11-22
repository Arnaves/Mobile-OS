package com.axiom.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Process-wide singletons. */
object Axiom {
    lateinit var models: ModelManager
    lateinit var engine: Engine
    lateinit var memory: MemoryStore
    lateinit var server: HudServer
    @Volatile var ready = false

    @Synchronized fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        models = ModelManager(app)
        engine = Engine(app, models)
        memory = MemoryStore(app)
        server = HudServer(app, models, engine, memory).also { it.start() }
        ready = true
    }
}

/** Keeps the AI core alive while the phone is in use. */
class AxiomService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("core", "AXIOM core", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "core")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("AXIOM core online")
            .setContentText("On-device intelligence ready · zero cloud")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        Axiom.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        if (Axiom.ready) Axiom.engine.stop()
        super.onDestroy()
    }
}
