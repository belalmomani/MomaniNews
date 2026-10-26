package com.momaninews.app.ui.components

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Region
import com.momaninews.app.data.model.Regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLogicTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L
    private fun n(id: Int, region: Region = Regions.Abeen, ageH: Double = 1.0, cat: String? = null) =
        NewsItem(id, "خبر $id", "", "", "م", "", region, category = cat, publishedAtMillis = now - (ageH * h).toLong())

    @Test fun angleStaysInsideRegionQuadrant() {
        Regions.all.forEachIndexed { i, r ->
            for (id in 1..200) {
                val a = RadarLogic.angleOf(n(id, r))
                assertTrue("id=$id angle=$a", a >= i * 90f + 12f && a < i * 90f + 78f)
            }
        }
    }

    @Test fun angleIsDeterministic_andUnknownRegionGoesToLastSector() {
        assertEquals(RadarLogic.angleOf(n(7)), RadarLogic.angleOf(n(7)))
        assertEquals(3, RadarLogic.sectorIndex("غير-معروف"))
    }

    @Test fun newerIsNearerTheRim_andShrinksWithAge() {
        val fresh = RadarLogic.radiusOf(n(1, ageH = 0.0), now)
        val mid = RadarLogic.radiusOf(n(1, ageH = 12.0), now)
        val old = RadarLogic.radiusOf(n(1, ageH = 24.0), now)
        assertTrue(fresh > mid && mid > old)
        assertEquals(0.88f, fresh, 0.001f); assertEquals(0.33f, old, 0.001f)
        // العاجل نافذته أطول فيبقى أقرب للحافة بالعمر نفسه
        assertTrue(RadarLogic.radiusOf(n(2, ageH = 24.0, cat = "death"), now) > old)
    }

    @Test fun blipFlags_urgentAndUnread() {
        val b = RadarLogic.blips(listOf(n(1, cat = "death"), n(2)), setOf(2), now)
        assertTrue(b[0].urgent && b[0].unread)
        assertTrue(!b[1].urgent && !b[1].unread)
    }

    @Test fun hit_picksNearestWithinThreshold_elseNull() {
        val blips = RadarLogic.blips(listOf(n(1), n(2, Regions.Sakhra)), emptySet(), now)
        val (x, y) = RadarLogic.position(blips[0], 250f)
        assertEquals(1, RadarLogic.hit(blips, x + 3f, y - 3f, 250f, 20f)?.news?.id)
        assertNull(RadarLogic.hit(blips, 125f, 125f, 250f, 5f))
        val (x2, y2) = RadarLogic.position(blips[1], 250f)
        assertNotNull(RadarLogic.hit(blips, x2, y2, 250f, 1f))
    }

    @Test fun ageLabel_buckets() {
        assertEquals("الآن", RadarLogic.ageLabel(now, now - 20_000))
        assertEquals("قبل 5 دقيقة", RadarLogic.ageLabel(now, now - 5 * 60_000))
        assertEquals("قبل 3 ساعة", RadarLogic.ageLabel(now, now - 3 * h))
        assertEquals("قبل 2 يوم", RadarLogic.ageLabel(now, now - 49 * h))
    }
}
