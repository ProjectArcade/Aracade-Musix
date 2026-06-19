package com.arcadesoftware.musix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class PlaybackService : Service() {
    companion object {
        var instance: PlaybackService? = null
        private var isServiceRunning = false
        
        fun start(context: Context) {
            if (!isServiceRunning) {
                val intent = Intent(context, PlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                isServiceRunning = true
            }
        }

        fun stop(context: Context) {
            if (isServiceRunning) {
                val intent = Intent(context, PlaybackService::class.java)
                context.stopService(intent)
                isServiceRunning = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        isServiceRunning = true

        val channelId = "music_playback"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for music playback"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
            .setContentTitle("Playing Music")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1001, notification)
        }

        val action = intent?.action
        if (action != null) {
            if (PlayerManager.exoPlayer == null) {
                PlayerManager.init(applicationContext)
            }
            when (action) {
                "com.arcadesoftware.musix.ACTION_PLAY" -> PlayerManager.playOrRecover(applicationContext)
                "com.arcadesoftware.musix.ACTION_PAUSE" -> PlayerManager.exoPlayer?.pause()
                "com.arcadesoftware.musix.ACTION_PREVIOUS" -> PlayerManager.playPrevious()
                "com.arcadesoftware.musix.ACTION_NEXT" -> PlayerManager.playNext()
                "com.arcadesoftware.musix.ACTION_DISMISS" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
                "com.arcadesoftware.musix.ACTION_LIKE" -> {
                    val song = PlayerManager.currentSong.value
                    if (song != null) {
                        val nowLiked = com.arcadesoftware.musix.db.LikedSongsManager.toggleLikeSong(applicationContext, song.id)
                        PlayerManager.isCurrentSongLiked.value = nowLiked
                        PlayerManager.triggerNotificationUpdate()
                    }
                }
                "com.arcadesoftware.musix.ACTION_REWIND_10" -> PlayerManager.seekBy(-10_000L)
                "com.arcadesoftware.musix.ACTION_FORWARD_10" -> PlayerManager.seekBy(10_000L)
            }
        } else {
            PlayerManager.triggerNotificationUpdate()
        }

        return START_STICKY
    }

    fun updateForegroundNotification(notification: Notification, isPlaying: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1001, notification)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            notificationManager.notify(1001, notification)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep service running when swiped from recents
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceRunning = false
    }
}
