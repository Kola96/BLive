package com.blive.tv.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MainFocusReducerTest {
    private val orchestrator = FocusOrchestrator()

    @Test
    fun `tab focus intent only highlights tab`() {
        val state = MainScreenState(
            isLoggedIn = true,
            selectedTab = MainTabType.Recommend,
            tabUiState = TabUiState(highlightedTab = MainTabType.Following),
            focusIntent = FocusIntent(
                target = FocusTarget.Tab(MainTabType.Following),
                token = 1L
            )
        )

        val action = orchestrator.resolveFocusIntent(
            state,
            FocusOrchestrator.Context(
                selectedTab = MainTabType.Recommend,
                liveListState = LiveListState.Content,
                liveRooms = sampleRooms(),
                isShowingSearchResult = false,
                hasLevel1Items = false,
                hasLevel2Items = false
            )
        )

        assertEquals(
            FocusResolution.Ready(FocusAction.FocusTab(MainTabType.Following)),
            action
        )
    }

    @Test
    fun `content intent restores recommend grid entrance position`() {
        val state = MainScreenState(
            isLoggedIn = true,
            selectedTab = MainTabType.Recommend,
            pendingContentFocusRequest = PendingContentFocusRequest(
                tab = MainTabType.Recommend,
                target = FocusTarget.Content(MainTabType.Recommend),
                token = 2L,
                autoFocusWhenReady = true
            ),
            gridRestoreStates = mapOf(
                MainTabType.Recommend to GridRestoreState(position = 2, roomId = 1003L),
                MainTabType.Following to GridRestoreState(),
                MainTabType.Partition to GridRestoreState(),
                MainTabType.Search to GridRestoreState()
            )
        )

        val action = orchestrator.resolvePendingContentFocus(
            state,
            FocusOrchestrator.Context(
                selectedTab = MainTabType.Recommend,
                liveListState = LiveListState.Content,
                liveRooms = sampleRooms(),
                isShowingSearchResult = false,
                hasLevel1Items = false,
                hasLevel2Items = false
            )
        )

        assertEquals(
            FocusResolution.Ready(
                FocusAction.FocusContent(MainTabType.Recommend, 2)
            ),
            action
        )
    }

    private fun sampleRooms(): List<LiveRoom> {
        return listOf(
            LiveRoom(1001L, "", "", "", "", ""),
            LiveRoom(1002L, "", "", "", "", ""),
            LiveRoom(1003L, "", "", "", "", "")
        )
    }
}
