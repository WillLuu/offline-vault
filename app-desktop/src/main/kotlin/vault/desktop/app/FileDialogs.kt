package vault.desktop.app

import java.awt.FileDialog
import java.io.File

// AWT 文件对话框：parent 须为 Frame/Dialog（FileDialog 构造器不收 Window）。
// MVP 传 null（AWT 自动用共享隐藏 Frame）；M6 可改传 Compose 主窗 Frame 以获得模态层级。

/** SAVE 模式：返回用户选择的目标文件；取消返回 null。 */
fun awtSaveDialog(parent: java.awt.Frame?, title: String, defaultFile: String): File? {
    val fd = FileDialog(parent, title, FileDialog.SAVE).apply { file = defaultFile }
    fd.isVisible = true // 阻塞式模态（AWT 惯例：EDT 上阻塞会泵事件，不冻结界面）
    val f = fd.file ?: return null
    val dir = fd.directory ?: return null
    return File(dir, f)
}

/** LOAD 模式：返回选中的源文件；取消返回 null。 */
fun awtOpenDialog(parent: java.awt.Frame?, title: String): File? {
    val fd = FileDialog(parent, title, FileDialog.LOAD)
    fd.isVisible = true
    val f = fd.file ?: return null
    val dir = fd.directory ?: return null
    return File(dir, f)
}
