package io.github.saggeldi.gps

interface PositionStore {
    fun insertPosition(position: Position, onComplete: (Boolean) -> Unit)
    fun selectFirstPosition(onComplete: (success: Boolean, position: Position?) -> Unit)
    fun selectAllPositions(onComplete: (success: Boolean, positions: List<Position>) -> Unit)
    fun deletePosition(id: Long, onComplete: (Boolean) -> Unit)
    fun deletePositions(ids: List<Long>, onComplete: (Boolean) -> Unit)
}
