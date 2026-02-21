package io.github.saggeldi.gps

interface PositionStore {
    fun insertPosition(position: Position, onComplete: (Boolean) -> Unit)
    fun selectFirstPosition(onComplete: (success: Boolean, position: Position?) -> Unit)
    fun deletePosition(id: Long, onComplete: (Boolean) -> Unit)
}
