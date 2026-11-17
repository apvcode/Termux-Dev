package com.apvcode.devx

import com.apvcode.devx.ui.Screen
import com.apvcode.devx.ui.backStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The system "back" must never close the app from the middle of the app, and never go round in a circle. */
class BackStepTest {
    @Test fun aChatOfANotebookGoesBackToTheNotebook() {
        assertEquals(Screen.Project, backStep(Screen.Chat, inSpace = true).screen)
    }

    @Test fun anOrdinaryChatLeavesTheApp() {
        assertNull(backStep(Screen.Chat, inSpace = false).screen)
    }

    @Test fun leavingTheNotebookOpensANewChatSoBackDoesNotGoRound() {
        val step = backStep(Screen.Project, inSpace = true)
        assertEquals(Screen.Chat, step.screen)
        assertTrue(step.newChat)
        // With the new chat ("inSpace" is no longer true) the next "back" leaves the app.
        assertNull(backStep(Screen.Chat, inSpace = false).screen)
    }

    @Test fun walkingBackFromANotebookChatEndsInThreePresses() {
        var screen: Screen? = Screen.Chat
        var inSpace = true
        var presses = 0
        while (screen != null && presses < 10) {
            val step = backStep(screen, inSpace)
            if (step.newChat) inSpace = false
            screen = step.screen
            presses++
        }
        assertNull(screen)
        assertEquals(3, presses)
    }

    @Test fun theCardsReturnToTheirNotebook() {
        assertEquals(Screen.Project, backStep(Screen.Cards, inSpace = false).screen)
        assertFalse(backStep(Screen.Cards, inSpace = false).newChat)
    }

    @Test fun everyPageOtherThanTheChatHasAWayBack() {
        for (screen in Screen.values()) {
            if (screen == Screen.Chat) continue
            for (inSpace in listOf(false, true)) {
                assertTrue("$screen has no way back", backStep(screen, inSpace).screen != null)
            }
        }
    }

    @Test fun backNeverLoopsFromAnyPage() {
        for (start in Screen.values()) {
            var screen: Screen? = start
            var inSpace = true
            var presses = 0
            while (screen != null && presses < 12) {
                val step = backStep(screen, inSpace)
                if (step.newChat) inSpace = false
                screen = step.screen
                presses++
            }
            assertNull("$start never leaves the app by going back", screen)
        }
    }

    @Test fun theProjectPageForANewProjectReturnsToTheChat() {
        assertEquals(Screen.Chat, backStep(Screen.ProjectEdit, inSpace = false, creatingProject = true).screen)
        assertEquals(Screen.Project, backStep(Screen.ProjectEdit, inSpace = false, creatingProject = false).screen)
    }
}
