package app.mergo.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

/**
 * MediaSession에서 재생 중인 영상 제목을 읽는다.
 * 접근성 노드에서 제목을 못 얻는 경우의 대안이며, '알림 접근' 권한(아래 [MediaListener])이 있어야 한다.
 */
class MediaTitleSource(private val context: Context) {
    data class Now(val title: String?, val playing: Boolean)

    fun read(pkg: String = YouTubeSignals.PKG): Now? {
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = try {
            msm.getActiveSessions(ComponentName(context, MediaListener::class.java))
        } catch (e: SecurityException) {
            return null
        }
        val c = sessions.firstOrNull { it.packageName == pkg } ?: return Now(null, false)
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        return Now(title, playing)
    }
}

/** MediaSession 목록을 읽을 권한을 얻기 위한 빈 알림 리스너. 알림 내용은 쓰지 않는다. */
class MediaListener : NotificationListenerService()
