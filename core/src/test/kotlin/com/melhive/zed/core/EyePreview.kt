package com.melhive.zed.core

import com.melhive.zed.core.eyes.EyeFrame
import java.awt.BasicStroke
import java.awt.Color
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Draws an [EyeFrame] into a picture file, so the eyes can be looked at without a phone. */
object EyePreview {
    fun render(frame: EyeFrame, w: Int, h: Int, file: File) {
        System.setProperty("java.awt.headless", "true")
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.BLACK; g.fillRect(0, 0, w, h)
        val clips = ArrayDeque<java.awt.Shape?>()
        for (op in frame.ops) {
            if (op.kind == EyeFrame.UNCLIP) { g.clip = clips.removeLast(); continue }
            val path = Path2D.Float()
            for (i in 0 until op.count) {
                val x = frame.pts[op.start + i * 2]; val y = frame.pts[op.start + i * 2 + 1]
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (op.closed) path.closePath()
            when (op.kind) {
                EyeFrame.CLIP -> { clips.addLast(g.clip); g.clip(path) }
                EyeFrame.FILL -> {
                    g.paint = if (op.colors != null) RadialGradientPaint(op.gx, op.gy, op.gr, op.stops, op.colors!!.map { Color(it, true) }.toTypedArray(), MultipleGradientPaint.CycleMethod.NO_CYCLE) else Color(op.color, true)
                    g.fill(path)
                }
                EyeFrame.STROKE -> {
                    g.paint = Color(op.color, true)
                    g.stroke = BasicStroke(op.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(path)
                }
            }
        }
        g.dispose()
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
    }
}
