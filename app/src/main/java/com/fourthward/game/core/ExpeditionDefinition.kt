package com.fourthward.game.core

/** A place the player must actually reach; its marker is the section of this 4D sphere. */
data class MissionCheckpoint(
    val id: String,
    val title: String,
    val volume: Sphere4,
    val colorArgb: Int,
)

data class MissionStage(val instruction: String, val hints: List<String>) {
    init {
        require(instruction.isNotBlank())
        require(hints.size == 3 && hints.all { it.isNotBlank() })
    }
}

/** The last stage returns to the exit after all required checkpoints have been visited. */
data class ExpeditionDefinition(
    val checkpoints: List<MissionCheckpoint>,
    val ordered: Boolean,
    val stages: List<MissionStage>,
) {
    init {
        require(checkpoints.isNotEmpty())
        require(checkpoints.map { it.id }.distinct().size == checkpoints.size)
        require(checkpoints.all { it.id.isNotBlank() && it.title.isNotBlank() })
        require(stages.size == checkpoints.size + 1)
    }
}
