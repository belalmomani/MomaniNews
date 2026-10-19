package com.momaninews.app.data.notifications

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** منطق نقي قابل للاختبار: تحويل رسالة FCM (بيانات فقط) إلى إشعار. */
object FcmLogic {
    const val TOPIC = "news"

    /** null إن كانت الرسالة ناقصة (بلا عنوان) أو معرّفها غير صالح. */
    fun parse(data: Map<String, String>): PushItem? {
        val title = data["title"]?.trim().orEmpty()
        if (title.isEmpty()) return null
        val id = data["id"]?.toLongOrNull() ?: return null
        return PushItem(id = id, title = title.take(120), body = data["body"]?.trim().orEmpty().take(500))
    }
}

/** الاشتراك في موضوع الأخبار العام بحسب خيار الإشعارات في الإعدادات. لا بيانات شخصية ترسل إلى FCM. */
object PushTopics {
    fun sync(context: Context) {
        val fm = try { FirebaseMessaging.getInstance() } catch (e: Exception) { return }
        if (PushPrefs.isEnabled(context)) fm.subscribeToTopic(FcmLogic.TOPIC) else fm.unsubscribeFromTopic(FcmLogic.TOPIC)
    }
}

/** يستقبل رسائل FCM فورياً (البيانات فقط) ويعرضها. المعرّف نفسه لإشعار الاستطلاع يستبدلها فلا تتكرر. */
class MomaniMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val ctx = applicationContext
        if (!PushPrefs.isEnabled(ctx)) return
        val item = FcmLogic.parse(message.data) ?: return
        PushNotifier.createChannel(ctx)
        if (!PushNotifier.hasPermission(ctx)) return
        PushNotifier.show(ctx, item)
        val last = PushPrefs.lastId(ctx)
        if (last >= 0 && item.id > last) PushPrefs.setLastId(ctx, item.id)   // حتى لا يعرضه الاستطلاع مرة ثانية
    }

    override fun onNewToken(token: String) {
        // نستخدم المواضيع (topics) فلا نخزّن الرمز. نُعيد الاشتراك فقط.
        PushTopics.sync(applicationContext)
    }
}
