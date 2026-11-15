package com.shixiaoshi0417.nekokem.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** The system's open and save dialogs. Paths are checked by Core, never here. */
interface FileChooser {
    fun open(): String?
    /** [directory] is where the dialog starts; null leaves that to the system. */
    fun save(suggested: String, directory: String?): String?
    /** Picks a folder; [directory] is where the dialog starts. */
    fun folder(directory: String?): String?
}

class AwtFileChooser(private val owner: () -> Frame?) : FileChooser {
    private fun show(mode: Int, suggested: String?, directory: String? = null): String? {
        val dialog = FileDialog(owner(), null as String?, mode)
        if (directory != null) dialog.directory = directory
        if (suggested != null) dialog.file = suggested
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory ?: "", name).absolutePath
    }
    override fun open() = show(FileDialog.LOAD, null)
    override fun save(suggested: String, directory: String?) = show(FileDialog.SAVE, suggested, directory)
    // AWT's dialog cannot pick folders on Windows or Linux; Swing's chooser can.
    override fun folder(directory: String?): String? {
        val chooser = JFileChooser(directory).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(owner()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }
}
