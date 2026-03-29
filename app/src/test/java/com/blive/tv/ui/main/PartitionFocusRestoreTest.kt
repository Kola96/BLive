package com.blive.tv.ui.main

import com.blive.tv.data.model.AreaLevel1
import com.blive.tv.data.model.AreaLevel2
import org.junit.Assert.assertEquals
import org.junit.Test

class PartitionFocusRestoreTest {
    private val orchestrator = FocusOrchestrator()

    @Test
    fun `partition level2 intent falls back to grid when level2 hidden`() {
        val state = MainScreenState(
            isLoggedIn = true,
            selectedTab = MainTabType.Partition,
            partitionAreas = sampleAreas(),
            selectedLevel1AreaId = 1,
            focusIntent = FocusIntent(
                target = FocusTarget.PartitionLevel2(index = 1),
                token = 3L
            ),
            partitionFocusState = PartitionFocusState(
                level1Index = 0,
                level2Index = 1,
                layer = PartitionFocusLayer.Grid,
                gridRestoreState = GridRestoreState(position = 1, roomId = 2002L)
            )
        )

        val action = orchestrator.resolveFocusIntent(
            state,
            FocusOrchestrator.Context(
                selectedTab = MainTabType.Partition,
                liveListState = LiveListState.Content,
                liveRooms = sampleRooms(),
                isShowingSearchResult = false,
                hasLevel1Items = true,
                hasLevel2Items = false
            )
        )

        assertEquals(FocusResolution.Ready(FocusAction.FocusGrid(1)), action)
    }

    @Test
    fun `partition content intent restores partition entrance`() {
        val state = MainScreenState(
            isLoggedIn = true,
            selectedTab = MainTabType.Partition,
            partitionAreas = sampleAreas(),
            selectedLevel1AreaId = 1,
            focusIntent = FocusIntent(
                target = FocusTarget.Content(MainTabType.Partition),
                token = 4L
            ),
            partitionFocusState = PartitionFocusState(
                level1Index = 0,
                level2Index = 1,
                layer = PartitionFocusLayer.Level2
            )
        )

        val action = orchestrator.resolveFocusIntent(
            state,
            FocusOrchestrator.Context(
                selectedTab = MainTabType.Partition,
                liveListState = LiveListState.Content,
                liveRooms = sampleRooms(),
                isShowingSearchResult = false,
                hasLevel1Items = true,
                hasLevel2Items = true
            )
        )

        assertEquals(
            FocusResolution.Ready(FocusAction.FocusContent(MainTabType.Partition)),
            action
        )
    }

    private fun sampleAreas(): List<AreaLevel1> {
        return listOf(
            AreaLevel1(
                id = 1,
                name = "网游",
                list = listOf(
                    AreaLevel2(id = "10", parentId = "1", name = "全部"),
                    AreaLevel2(id = "11", parentId = "1", name = "英雄联盟")
                )
            )
        )
    }

    private fun sampleRooms(): List<LiveRoom> {
        return listOf(
            LiveRoom(2001L, "", "", "", "", ""),
            LiveRoom(2002L, "", "", "", "", "")
        )
    }
}
