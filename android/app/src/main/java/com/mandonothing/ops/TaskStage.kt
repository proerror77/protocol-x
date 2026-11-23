package com.mandonothing.ops

internal enum class TaskStage(val label: String) {
    ALL("全部"),
    WAITING("待接单"),
    ACTIVE("进行中"),
    DONE("已完成"),
    DECLINED("无法响应");

    companion object {
        fun classify(awaitingAcknowledgement: Boolean, steps: List<String>, declined: Boolean = false): TaskStage = when {
            declined -> DECLINED
            awaitingAcknowledgement -> WAITING
            steps.isNotEmpty() && steps.all(::isCompleted) -> DONE
            else -> ACTIVE
        }

        fun isCompleted(status: String): Boolean = status in setOf("done", "completed", "confirmed")
    }
}
