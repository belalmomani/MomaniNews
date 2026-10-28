package com.momaninews.app.admin

import com.momaninews.app.data.model.User
import com.momaninews.app.data.model.VerificationStatus
import com.momaninews.app.ui.screens.home.DrawerAction
import com.momaninews.app.ui.screens.home.DrawerLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerLogicTest {
    private fun actions(u: User?) = DrawerLogic.entries(u).map { it.action }
    private fun user(admin: Boolean = false, v: VerificationStatus = VerificationStatus.NONE, demo: Boolean = false) =
        User(id = "1", displayName = "أحمد", email = "a@b.c", verification = v, isDemo = demo, isAdmin = admin)

    @Test fun guestSeesRegionsSettingsAndSignIn() {
        val a = actions(null)
        assertEquals(DrawerAction.HOME, a.first())
        assertEquals(4, a.count { it == DrawerAction.REGION })
        assertTrue(DrawerAction.SIGN_IN in a); assertFalse(DrawerAction.SIGN_OUT in a)
        assertFalse(DrawerAction.NOTIFICATIONS in a); assertFalse(DrawerAction.ADMIN in a); assertFalse(DrawerAction.MY_POSTS in a)
        assertTrue(DrawerAction.STORE in a)   // المتجر للجميع
    }

    @Test fun userSeesAccountToolsButNotAdmin() {
        val a = actions(user())
        assertTrue(DrawerAction.NOTIFICATIONS in a); assertTrue(DrawerAction.AD_REQUEST in a); assertTrue(DrawerAction.MY_POSTS in a)
        assertTrue(DrawerAction.VERIFICATION in a); assertTrue(DrawerAction.SIGN_OUT in a)
        assertFalse(DrawerAction.ADMIN in a)
    }

    @Test fun adminSeesAdminAndApprovedOrDemoHideVerification() {
        assertTrue(DrawerAction.ADMIN in actions(user(admin = true)))
        assertFalse(DrawerAction.VERIFICATION in actions(user(v = VerificationStatus.APPROVED)))
        assertFalse(DrawerAction.VERIFICATION in actions(user(demo = true)))
    }

    @Test fun initialLetter() {
        assertEquals("أ", DrawerLogic.initial(" أحمد"))
        assertEquals("م", DrawerLogic.initial(""))
    }

    @Test fun sections_guest() {
        val s = DrawerLogic.sections(null)
        assertEquals(5, s.shortcuts.size)                       // الرئيسية + 4 مناطق
        assertEquals(listOf(DrawerAction.SAVED, DrawerAction.STORE), s.main.map { it.action })
        assertEquals(listOf(DrawerAction.SETTINGS), s.more.map { it.action })
        assertEquals(DrawerAction.SIGN_IN, s.footer?.action)
    }

    @Test fun sections_adminUserGetsToolsAndAdminUnderMore() {
        val s = DrawerLogic.sections(user(admin = true))
        assertEquals(listOf(DrawerAction.SAVED, DrawerAction.STORE, DrawerAction.NOTIFICATIONS, DrawerAction.MY_POSTS, DrawerAction.AD_REQUEST), s.main.map { it.action })
        assertEquals(listOf(DrawerAction.VERIFICATION, DrawerAction.SETTINGS, DrawerAction.ADMIN), s.more.map { it.action })
        assertEquals(DrawerAction.SIGN_OUT, s.footer?.action)
    }

    @Test fun sections_coverEveryEntryExactlyOnce() {
        val u = user(admin = true)
        val s = DrawerLogic.sections(u)
        val all = s.shortcuts + s.main + s.more + listOfNotNull(s.footer)
        assertEquals(DrawerLogic.entries(u).map { it.action to it.regionId }.sortedBy { it.toString() }, all.map { it.action to it.regionId }.sortedBy { it.toString() })
    }
}
