package com.vibetools.pencil.model

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import java.util.UUID

/** Content stays owned by the group in both display states. Coordinates are page coordinates. */
data class FoldedNote(
    val id: String = UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    val expanded: Boolean = false,
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextBox> = emptyList(),
    val images: List<CanvasImage> = emptyList(),
    val links: List<StoredLink> = emptyList(),
    val summaries: List<StoredSummary> = emptyList()
) {
    /** Move only the visible label. The captured note coordinates remain unchanged. */
    fun shifted(dx: Float, dy: Float) = copy(x=x+dx, y=y+dy)
    fun deepCopy() = copy(
        strokes=strokes.map { it.copy(points=it.points.map { p -> PointF(p.x,p.y) }.toMutableList()) },
        texts=texts.map { it.copy() },
        images=images.map { it.copy() },
        links=links.map { it.copy() },
        summaries=summaries.map { it.copy() }
    )
}

/** Shared drawing and hit bounds keep the label's touch target aligned at every zoom. */
object FoldedNoteLabel {
    const val TEXT = "（⭐此处有笔记）"
    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=20f;typeface=android.graphics.Typeface.DEFAULT }
    fun bounds(note: FoldedNote): RectF {
        val p=paint()
        return RectF(note.x,note.y,note.x+p.measureText(TEXT)+20f,note.y+36f)
    }
    fun draw(canvas: Canvas,note: FoldedNote) {
        val p=paint();val r=bounds(note)
        p.color=if(note.expanded)Color.rgb(220,237,255) else Color.rgb(255,240,190)
        canvas.drawRoundRect(r,8f,8f,p)
        p.color=Color.rgb(95,70,25)
        canvas.drawText(TEXT,r.left+10f,r.centerY()-(p.ascent()+p.descent())/2f,p)
    }
}
