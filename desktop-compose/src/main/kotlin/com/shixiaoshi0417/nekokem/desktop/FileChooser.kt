package com.shixiaoshi0417.nekokem.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The system's open and save dialogs. Paths are checked by Core, never here. */
interface FileChooser {
    fun open(): String?
    fun save(suggested: String): String?
}

class AwtFileChooser(private val owner: () -> Frame?) : FileChooser {
    private fun show(mode: Int, suggested: String?): String? {
        val dialog = FileDialog(owner(), null as String?, mode)
        if (suggested != null) dialog.file = suggested
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory ?: "", name).absolutePath
    }
    override fun open() = show(FileDialog.LOAD, null)
    override fun save(suggested: String) = show(FileDialog.SAVE, suggested)
}
