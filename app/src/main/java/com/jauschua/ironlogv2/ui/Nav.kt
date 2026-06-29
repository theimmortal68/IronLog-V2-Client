package com.jauschua.ironlogv2.ui

object Routes {
    const val MOVEMENTS = "movements"
    const val MOVEMENT_DETAIL = "movement/{id}"
    const val BANDS = "bands"
    const val AUTOREGULATE = "autoregulate"
    const val CAPTURE = "capture"

    fun movementDetail(id: Int): String = "movement/$id"
}
