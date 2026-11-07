package com.vibetools.pencil.model

import android.graphics.PointF

/**
 * 文件类型
 */
enum class FileType {
    PDF,        // PDF电子书
    BLANK,      // 空白背景
    IMAGE       // 图库导入的图片背景
}

/**
 * 笔记文件
 */
data class NoteFile(
    val id: String,
    val name: String,
    val type: FileType,
    val parentFolderId: String? = null,  // 父文件夹ID，null表示根目录
    val pages: MutableList<Page> = mutableListOf(),
    val bookmarks: MutableList<Bookmark> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 文件夹
 */
data class Folder(
    val id: String,
    val name: String,
    val parentFolderId: String? = null,
    val isSpecial: Boolean = false,  // 是否是特殊文件夹（草稿纸、合并页等）
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 页面
 */
data class Page(
    val id: String,
    val fileId: String,
    val pageNumber: Int,
    val background: Background,
    val strokes: MutableList<Stroke> = mutableListOf(),
    val textBoxes: MutableList<TextBox> = mutableListOf(),
    val hyperlinks: MutableList<Hyperlink> = mutableListOf(),
    val centerX: Float = 0f,  // 页面中心点X坐标
    val centerY: Float = 0f   // 页面中心点Y坐标
)

/**
 * 背景
 */
sealed class Background {
    object Blank : Background()
    data class Image(val imagePath: String) : Background()
    data class PDF(val pdfPath: String, val pageIndex: Int) : Background()
}

/**
 * 工具类型
 */
enum class Tool {
    PEN,            // 钢笔
    PENCIL,         // 铅笔
    HIGHLIGHTER     // 荧光笔
}

/**
 * 笔触
 */
data class Stroke(
    val id: String,
    val tool: Tool,
    val color: Int,
    val width: Float,
    val alpha: Int = 255,  // 透明度 (0-255)，荧光笔需要半透明
    val points: MutableList<PointF> = mutableListOf(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 文本框
 */
data class TextBox(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float,
    val color: Int = android.graphics.Color.BLACK,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 书签
 */
data class Bookmark(
    val id: String,
    val name: String,
    val fileId: String,
    val pageId: String,
    val x: Float,  // 定位点X坐标
    val y: Float,  // 定位点Y坐标
    val timestamp: Long = System.currentTimeMillis(),
    val temporary: Boolean = false
)

/**
 * 超链接类型
 */
sealed class HyperlinkType {
    data class Internal(val targetFileId: String, val targetBookmarkId: String? = null) : HyperlinkType()  // 应用内链接
    data class NumberTag(val number: String) : HyperlinkType()  // 编号标签
    data class External(val url: String) : HyperlinkType()  // 互联网链接
}

/**
 * 超链接
 */
data class Hyperlink(
    val id: String,
    val x: Float,
    val y: Float,
    val type: HyperlinkType,
    val displayText: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 总结（特殊的书签）
 */
data class Summary(
    val id: String,
    val fileId: String,
    val pageId: String,
    val x: Float,
    val y: Float,
    val chapterTitle: String,
    val conceptsChecklist: String,
    val memoryIndex: String? = null,
    val problemSolvingIndex: String? = null,
    val mistakesIndex: String? = null,
    val isExpanded: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 橡皮擦类型
 */
enum class EraserTool {
    ERASER,             // 橡皮擦：仅清除铅笔
    SAND_ERASER,        // 砂擦：清除钢笔、铅笔、荧光笔、文本
    CORRECTION_FLUID    // 涂改液：清除所有内容（包括背景）
}

/**
 * 框选区域
 */
sealed class SelectionRegion {
    data class Rectangle(val left: Float, val top: Float, val right: Float, val bottom: Float) : SelectionRegion()
    data class Freeform(val points: List<PointF>) : SelectionRegion()
}

/**
 * 剪贴板项
 */
data class ClipboardItem(
    val id: String,
    val region: SelectionRegion,
    val content: ByteArray,  // 截图的bitmap数据
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ClipboardItem

        if (id != other.id) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
