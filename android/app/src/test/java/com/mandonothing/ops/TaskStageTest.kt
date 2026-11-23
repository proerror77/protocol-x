package com.mandonothing.ops

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskStageTest {
    @Test
    fun acknowledgementTakesPriorityOverPreconfirmedMilestones() {
        assertEquals(TaskStage.WAITING, TaskStage.classify(true, listOf("done")))
    }

    @Test
    fun currentAndPendingMilestonesKeepTheTaskActive() {
        assertEquals(TaskStage.ACTIVE, TaskStage.classify(false, listOf("done", "current", "pending")))
        assertEquals(TaskStage.ACTIVE, TaskStage.classify(false, emptyList()))
    }

    @Test
    fun confirmedMilestonesCloseTheTask() {
        assertEquals(TaskStage.DONE, TaskStage.classify(false, listOf("done", "confirmed", "completed")))
    }
}
