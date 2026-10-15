package com.momaninews.app.data.sample

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Regions

/**
 * بيانات تجريبية محلية لعرض شكل الواجهة وتجربة البحث والتصفية فقط.
 * ليست أخباراً حقيقية، وستُستبدل ببيانات الخادم في المرحلة الثالثة.
 */
object SampleNews {

    private const val SAMPLE_BODY =
        "هذا نص تجريبي يوضح شكل الفقرة الأولى من محتوى الخبر الكامل داخل صفحة التفاصيل، وليس خبراً حقيقياً.\n\n" +
            "هذه فقرة تجريبية ثانية لاختبار المسافات بين الأسطر وقراءة الخط العربي على الشاشات الصغيرة والمتوسطة.\n\n" +
            "هذه فقرة تجريبية ثالثة. سيحل محتوى الأخبار المعتمدة من الإدارة محل هذه النصوص عند ربط الخادم."

    val items: List<NewsItem> = listOf(
        NewsItem(
            id = 1,
            title = "[تجريبي] خبر اجتماعي لعرض شكل البطاقة",
            excerpt = "نص تجريبي قصير يوضح مكان المقتطف داخل بطاقة الخبر، وليس خبراً حقيقياً.",
            content = SAMPLE_BODY,
            source = "مصدر تجريبي",
            dateLabel = "تاريخ تجريبي",
            region = Regions.Abeen,
            isSample = true
        ),
        NewsItem(
            id = 2,
            title = "[تجريبي] خبر رياضي بعنوان أطول قليلاً لاختبار التفاف النص العربي على أكثر من سطر داخل البطاقة",
            excerpt = "نص تجريبي آخر لاختبار الارتفاع والمسافات وقراءة الخط العربي على الهواتف المتوسطة.",
            content = SAMPLE_BODY,
            source = "مصدر تجريبي",
            dateLabel = "تاريخ تجريبي",
            region = Regions.Abelin,
            isSample = true
        ),
        NewsItem(
            id = 3,
            title = "[تجريبي] خبر خدمي لاختبار التمرير في القائمة",
            excerpt = "نص تجريبي يوضح شكل بطاقة منطقة صخرة، وليس خبراً حقيقياً.",
            content = SAMPLE_BODY,
            source = "مصدر تجريبي",
            dateLabel = "تاريخ تجريبي",
            region = Regions.Sakhra,
            isSample = true
        ),
        NewsItem(
            id = 4,
            title = "[تجريبي] خبر ثقافي",
            excerpt = "نص تجريبي قصير.",
            content = SAMPLE_BODY,
            source = "مصدر تجريبي",
            dateLabel = "تاريخ تجريبي",
            region = Regions.Abeen,
            isSample = true
        ),
        NewsItem(
            id = 5,
            title = "[تجريبي] خبر اقتصادي",
            excerpt = "نص تجريبي لاختبار التصفية حسب المنطقة والبحث في المقتطف.",
            content = SAMPLE_BODY,
            source = "مصدر تجريبي",
            dateLabel = "تاريخ تجريبي",
            region = Regions.Abelin,
            isSample = true
        ),
        NewsItem(
            id = 6,
            title = "[تجريبي] خبر تعليمي",
            excerpt = "نص تجريبي أخير في القائمة.",
            content = SAMPLE_BODY,
            source = "مصدر تجريبي",
            dateLabel = "تاريخ تجريبي",
            region = Regions.Sakhra,
            isSample = true
        )
    )
}
