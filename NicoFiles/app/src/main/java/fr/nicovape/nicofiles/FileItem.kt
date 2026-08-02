package fr.nicovape.nicofiles

import java.io.File

enum class SortMode {
    NAME,
    DATE,
    SIZE,
    TYPE
}

data class ClipboardOperation(
    val files: List<File>,
    val move: Boolean
)
