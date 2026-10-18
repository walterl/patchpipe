package dev.patchpipe.app.service

import android.content.Intent
import dev.patchpipe.app.data.DEFAULT_AUTO_START
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStartTest {
    @Test
    fun defaultIsOff() {
        assertFalse(DEFAULT_AUTO_START)
        assertFalse(shouldStartOnBoot(DEFAULT_AUTO_START, Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun optedInStartsOnlyAfterUnlockBoot() {
        assertTrue(shouldStartOnBoot(true, Intent.ACTION_BOOT_COMPLETED))
        assertFalse(shouldStartOnBoot(true, Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFalse(shouldStartOnBoot(true, null))
        assertFalse(shouldStartOnBoot(true, Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun optedOutDoesNotStart() {
        assertFalse(shouldStartOnBoot(false, Intent.ACTION_BOOT_COMPLETED))
    }
}
