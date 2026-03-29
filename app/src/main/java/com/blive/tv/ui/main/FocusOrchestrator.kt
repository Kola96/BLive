package com.blive.tv.ui.main

sealed interface FocusAction {
    data class FocusTab(val tab: MainTabType) : FocusAction
    data class FocusContent(val tab: MainTabType, val targetPosition: Int = 0) : FocusAction
    data class FocusGrid(val position: Int) : FocusAction
    data class FocusPartitionLevel1(val index: Int) : FocusAction
    data class FocusPartitionLevel2(val index: Int) : FocusAction
    object FocusSearchInput : FocusAction
    data class FocusMineAction(val action: MineActionTarget) : FocusAction
}

sealed interface FocusResolution {
    object None : FocusResolution
    object Pending : FocusResolution
    data class Ready(val action: FocusAction) : FocusResolution
}

class FocusOrchestrator {
    data class Context(
        val selectedTab: MainTabType,
        val liveListState: LiveListState,
        val liveRooms: List<LiveRoom>,
        val isShowingSearchResult: Boolean,
        val hasLevel1Items: Boolean,
        val hasLevel2Items: Boolean
    )

    fun resolveFocusIntent(state: MainScreenState, context: Context): FocusResolution {
        return resolveTarget(state.focusIntent.target, state, context)
    }

    fun resolvePendingContentFocus(state: MainScreenState, context: Context): FocusResolution {
        val request = state.pendingContentFocusRequest
        if (request.token == 0L) {
            return FocusResolution.None
        }
        return resolveTarget(request.target, state, context)
    }

    private fun resolveTarget(
        target: FocusTarget,
        state: MainScreenState,
        context: Context
    ): FocusResolution {
        return when (target) {
            FocusTarget.None -> FocusResolution.None
            is FocusTarget.Tab -> FocusResolution.Ready(FocusAction.FocusTab(target.tab))
            is FocusTarget.Content -> resolveContentAction(target.tab, state, context)
            is FocusTarget.Grid -> resolveGridAction(target, state, context)
            is FocusTarget.PartitionLevel1 -> {
                if (context.hasLevel1Items) {
                    FocusResolution.Ready(FocusAction.FocusPartitionLevel1(target.index))
                } else {
                    FocusResolution.Pending
                }
            }
            is FocusTarget.PartitionLevel2 -> {
                if (context.hasLevel2Items) {
                    FocusResolution.Ready(FocusAction.FocusPartitionLevel2(target.index))
                } else if (context.liveListState == LiveListState.Loading) {
                    FocusResolution.Pending
                } else {
                    FocusResolution.Ready(FocusAction.FocusGrid(resolvePartitionGridPosition(state, context.liveRooms)))
                }
            }
            FocusTarget.SearchInput -> FocusResolution.Ready(FocusAction.FocusSearchInput)
            is FocusTarget.MineAction -> FocusResolution.Ready(FocusAction.FocusMineAction(target.action))
        }
    }

    private fun resolveContentAction(
        tab: MainTabType,
        state: MainScreenState,
        context: Context
    ): FocusResolution {
        return when (tab) {
            MainTabType.Mine -> FocusResolution.Ready(FocusAction.FocusMineAction(MineActionTarget.Settings))
            MainTabType.Partition -> when (state.partitionFocusState.layer) {
                PartitionFocusLayer.Level1 -> {
                    if (context.hasLevel1Items) {
                        FocusResolution.Ready(FocusAction.FocusContent(MainTabType.Partition))
                    } else {
                        FocusResolution.Pending
                    }
                }
                PartitionFocusLayer.Level2 -> {
                    if (context.hasLevel2Items) {
                        FocusResolution.Ready(FocusAction.FocusContent(MainTabType.Partition))
                    } else if (!context.hasLevel1Items) {
                        FocusResolution.Pending
                    } else {
                        FocusResolution.Ready(FocusAction.FocusContent(MainTabType.Partition))
                    }
                }
                PartitionFocusLayer.Grid -> {
                    if (context.hasLevel2Items || context.hasLevel1Items) {
                        FocusResolution.Ready(FocusAction.FocusContent(MainTabType.Partition))
                    } else {
                        resolveGridOrPending(state, context, resolvePartitionGridPosition(state, context.liveRooms))
                    }
                }
            }
            MainTabType.Search -> {
                if (!context.isShowingSearchResult) {
                    FocusResolution.Ready(FocusAction.FocusSearchInput)
                } else if (context.liveListState == LiveListState.Content && context.liveRooms.isNotEmpty()) {
                    FocusResolution.Ready(
                        FocusAction.FocusContent(
                            tab,
                            resolveGridPosition(state, MainTabType.Search, context.liveRooms)
                        )
                    )
                } else if (context.liveListState == LiveListState.Loading) {
                    FocusResolution.Pending
                } else {
                    FocusResolution.Ready(FocusAction.FocusContent(tab, 0))
                }
            }
            MainTabType.Recommend, MainTabType.Following -> {
                if (context.liveListState == LiveListState.Content && context.liveRooms.isNotEmpty()) {
                    FocusResolution.Ready(
                        FocusAction.FocusContent(
                            tab,
                            resolveGridPosition(state, tab, context.liveRooms)
                        )
                    )
                } else if (context.liveListState == LiveListState.Loading) {
                    FocusResolution.Pending
                } else {
                    FocusResolution.Ready(FocusAction.FocusContent(tab, resolveGridPosition(state, tab, context.liveRooms)))
                }
            }
            MainTabType.Login -> FocusResolution.Ready(FocusAction.FocusTab(MainTabType.Login))
        }
    }

    private fun resolveGridAction(
        target: FocusTarget.Grid,
        state: MainScreenState,
        context: Context
    ): FocusResolution {
        if (context.liveRooms.isEmpty()) {
            if (context.liveListState == LiveListState.Loading) {
                return FocusResolution.Pending
            }
            return resolveContentAction(target.tab, state, context)
        }
        val matchedPosition = target.roomId?.let { roomId ->
            context.liveRooms.indexOfFirst { it.roomId == roomId }.takeIf { it >= 0 }
        }
        val restorePosition = matchedPosition ?: target.position
        return FocusResolution.Ready(FocusAction.FocusGrid(restorePosition.coerceIn(0, context.liveRooms.lastIndex)))
    }

    private fun resolveGridOrPending(
        state: MainScreenState,
        context: Context,
        targetPosition: Int
    ): FocusResolution {
        return if (context.liveListState == LiveListState.Loading || context.liveRooms.isEmpty()) {
            FocusResolution.Pending
        } else {
            FocusResolution.Ready(
                FocusAction.FocusGrid(
                    targetPosition.coerceIn(0, context.liveRooms.lastIndex)
                )
            )
        }
    }

    private fun resolveGridPosition(
        state: MainScreenState,
        tab: MainTabType,
        liveRooms: List<LiveRoom>
    ): Int {
        if (liveRooms.isEmpty()) {
            return 0
        }
        val restoreState = state.gridRestoreStates[tab] ?: GridRestoreState()
        restoreState.roomId?.let { roomId ->
            val matchedIndex = liveRooms.indexOfFirst { it.roomId == roomId }
            if (matchedIndex >= 0) {
                return matchedIndex
            }
        }
        return restoreState.position.coerceIn(0, liveRooms.lastIndex)
    }

    private fun resolvePartitionGridPosition(
        state: MainScreenState,
        liveRooms: List<LiveRoom>
    ): Int {
        if (liveRooms.isEmpty()) {
            return 0
        }
        val restoreState = state.partitionFocusState.gridRestoreState
        restoreState.roomId?.let { roomId ->
            val matchedIndex = liveRooms.indexOfFirst { it.roomId == roomId }
            if (matchedIndex >= 0) {
                return matchedIndex
            }
        }
        return restoreState.position.coerceIn(0, liveRooms.lastIndex)
    }
}
