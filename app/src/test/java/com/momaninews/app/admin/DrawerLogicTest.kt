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
        assertFalse(DrawerAction.NOTIFICATIONS in a); assertFalse(DrawerAction.ADMIN in a)
    }

    @Test fun userSeesAccountToolsButNotAdmin() {
        val a = actions(user())
        assertTrue(DrawerAction.NOTIFICATIONS in a); assertTrue(DrawerAction.AD_REQUEST in a)
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
}
