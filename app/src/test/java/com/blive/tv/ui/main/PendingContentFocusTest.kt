package com.blive.tv.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingContentFocusTest {
    private val orchestrator = FocusOrchestrator()

    @Test
    fun `search pending input focus resolves immediately`() {
        val state = MainScreenState(
            isLoggedIn = true,
            selectedTab = MainTabType.Search,
            pendingContentFocusRequest = PendingContentFocusRequest(
                tab = MainTabType.Search,
                target = FocusTarget.SearchInput,
                token = 7L,
                autoFocusWhenReady = true
            )
        )

        val action = orchestrator.resolvePendingContentFocus(
            state,
            FocusOrchestrator.Context(
                selectedTab = MainTabType.Search,
                liveListState = LiveListState.Loading,
                liveRooms = emptyList(),
                isShowingSearchResult = false,
                hasLevel1Items = false,
                hasLevel2Items = false
            )
        )

        assertEquals(FocusResolution.Ready(FocusAction.FocusSearchInput), action)
    }

    @Test
    fun `recommend pending content focus resolves to grid entrance after content ready`() {
        val state = MainScreenState(
            isLoggedIn = true,
            selectedTab = MainTabType.Recommend,
            pendingContentFocusRequest = PendingContentFocusRequest(
                tab = MainTabType.Recommend,
                target = FocusTarget.Content(MainTabType.Recommend),
                token = 8L,
                autoFocusWhenReady = true
            ),
            gridRestoreStates = mapOf(
                MainTabType.Recommend to GridRestoreState(position = 1, roomId = 1002L),
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
                FocusAction.FocusContent(MainTabType.Recommend, 1)
            ),
            action
        )
    }

    @Test
    fun `recommend pending content focus waits while loading`() {
        val state = MainScreenState(
            isLoggedIn = true,
            selectedTab = MainTabType.Recommend,
            pendingContentFocusRequest = PendingContentFocusRequest(
                tab = MainTabType.Recommend,
                target = FocusTarget.Content(MainTabType.Recommend),
                token = 9L,
                autoFocusWhenReady = true
            )
        )

        val action = orchestrator.resolvePendingContentFocus(
            state,
            FocusOrchestrator.Context(
                selectedTab = MainTabType.Recommend,
                liveListState = LiveListState.Loading,
                liveRooms = emptyList(),
                isShowingSearchResult = false,
                hasLevel1Items = false,
                hasLevel2Items = false
            )
        )

        assertEquals(FocusResolution.Pending, action)
    }

    private fun sampleRooms(): List<LiveRoom> {
        return listOf(
            LiveRoom(1001L, "", "", "", "", ""),
            LiveRoom(1002L, "", "", "", "", ""),
            LiveRoom(1003L, "", "", "", "", "")
        )
    }
}
