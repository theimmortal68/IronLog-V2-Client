package com.jauschua.ironlogv2.ui

object Routes {
    const val MOVEMENTS = "movements"
    const val MOVEMENT_DETAIL = "movement/{id}"
    const val BANDS = "bands"
    const val AUTOREGULATE = "autoregulate"

    fun movementDetail(id: Int): String = "movement/$id"
}
