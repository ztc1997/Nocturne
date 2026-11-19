package com.ztc1997.nocturne.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.ztc1997.nocturne.core.Placeholder
import kotlin.math.max
import kotlin.math.min

/**
 * 星月字形的唯一绘制实现。
 *
 * 为什么抽出来（2026-11-19）：星月有两个完全不同的落点——
 *  - **文本链路**（桌面/锁屏 SET_TEXT/状态栏）：宿主用字体排版，靠
 *    [StarMoonSpan] 的 ReplacementSpan 回调在文字位置上画；
 *  - **引擎链路**（第三方主题的锁屏/AOD）：VariUIEngine / EGView 用位图或
 *    `canvas.drawText` 自己画数字，没有文本可替换，只能在绘制方法里按原
 *    数字的位置和尺寸直接画。
 *
 * 两条链路的形状必须一模一样，否则同一台手机上桌面和锁屏是两种星月。
 * 所以形状只有这一份实现，[StarMoonSpan] 与 [EngineClockPainter] 都调它。
 *
 * 坐标约定：字形以 (cx, cy) 为中心，capH 是「数字墨迹高度」（= 直径），
 * cellW 是该位数字的宽度（窄位如 "1" 会把星横向压扁，不越出格子）。
 */
object StarMoonGlyph {

    private val digitBounds = Rect()
    private val path = Path()
    private val cutPath = Path()
    private val outerRect = RectF()
    private val cutRect = RectF()

    /** 数字 → 该分钟下应为星还是月。与 [Placeholder.mask] 的散列同源。 */
    fun isStar(minuteSeed: Long, digit: Int): Boolean =
        Placeholder.glyph(minuteSeed, digit) == Placeholder.STAR

    /**
     * 以 (cx, cy) 为中心画一个字形。
     *
     * @param capH 字形高度（数字墨迹高），星月直径同此值
     * @param cellW 单元格宽度（原数字的 advance/位图宽）
     */
    fun draw(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        capH: Float,
        cellW: Float,
        star: Boolean,
    ) {
        if (!(capH > 0f) || !(cellW > 0f)) return
        val style = paint.style
        paint.style = Paint.Style.FILL
        path.reset()
        if (star) {
            drawStar(path, cx, cy, capH, cellW)
        } else {
            drawMoon(path, cutPath, outerRect, cutRect, cx, cy, capH, cellW)
        }
        canvas.drawPath(path, paint)
        paint.style = style
    }

    /**
     * 把字形画进一个矩形格子（引擎的数字位图格子：left/top/right/bottom）。
     * 格子就是原数字的包围盒，因此星月与原数字同高同宽同位。
     */
    fun drawInBox(
        canvas: Canvas,
        paint: Paint,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        star: Boolean,
    ) {
        val w = right - left
        val h = bottom - top
        if (!(w > 0f) || !(h > 0f)) return
        draw(canvas, paint, left + w / 2f, top + h / 2f, h, w, star)
    }

    /**
     * 取「数字墨迹高」：该 paint 下 "0" 的 ink 高，也就是真实数字的高度。
     * 文本链路（span）与 AOD 的 drawText 链路都用它，保证与真实数字等高。
     */
    fun capHeight(paint: Paint): Float {
        paint.getTextBounds("0", 0, 1, digitBounds)
        return (digitBounds.bottom - digitBounds.top).toFloat()
    }

    /** 数字墨迹带的垂直中心相对基线的偏移（负值向上）。 */
    fun capCenterOffset(paint: Paint): Float {
        paint.getTextBounds("0", 0, 1, digitBounds)
        return (digitBounds.top + digitBounds.bottom) / 2f
    }

    /**
     * 实芯四芒星（凹边曲线，同应用图标风格）。
     * 高 = 数字 ink 高；宽超出窄单元格时按 cell 等比压缩横向（如 "1" 位）。
     * 臂根用双控制点贴近中心的 cubic 贝塞尔收束，凹得深才不会画成胖菱形
     * （真机 0.22r quad 版实测过肥，已改）。
     */
    private fun drawStar(path: Path, cx: Float, cy: Float, capH: Float, cellW: Float) {
        val r = capH * 0.50f
        val maxHalfW = cellW * 0.5f * 0.92f
        val sx = if (r > maxHalfW) maxHalfW / r else 1f
        val ri = r * 0.10f
        path.moveTo(cx, cy - r)
        path.cubicTo(cx + ri * sx, cy - ri, cx + ri * sx, cy - ri, cx + r * sx, cy)
        path.cubicTo(cx + ri * sx, cy + ri, cx + ri * sx, cy + ri, cx, cy + r)
        path.cubicTo(cx - ri * sx, cy + ri, cx - ri * sx, cy + ri, cx - r * sx, cy)
        path.cubicTo(cx - ri * sx, cy - ri, cx - ri * sx, cy - ri, cx, cy - r)
        path.close()
    }

    /**
     * 实芯月牙（开口朝右，近似 ☾）：大椭圆挖掉右上偏移的同比例椭圆。
     * 水平半径受单元格宽度约束（宽 < 高），避免月牙比数字列宽。
     * 挖除圆偏移 0.55rx、同半径 → 月牙厚度 ≈ 0.45rx，尖角收干净
     * （真机 0.62 偏移 + 0.92 半径版实测过厚过钝，已改）。
     */
    private fun drawMoon(
        path: Path,
        cutPath: Path,
        outerRect: RectF,
        cutRect: RectF,
        cx: Float,
        cy: Float,
        capH: Float,
        cellW: Float,
    ) {
        val ry = capH * 0.50f
        val rx = min(ry, cellW * 0.40f)
        outerRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        path.addOval(outerRect, Path.Direction.CW)
        // 挖除圆心向右上偏移，留下左侧月牙
        val cutCx = cx + rx * 0.55f
        val cutCy = cy - ry * 0.12f
        cutRect.set(cutCx - rx, cutCy - ry, cutCx + rx, cutCy + ry)
        cutPath.addOval(cutRect, Path.Direction.CW)
        path.op(cutPath, Path.Op.DIFFERENCE)
        cutPath.reset()
        // 挖除后月牙 ink 全在单元格左半边（外椭圆以 cx 居中、挖除圆右偏），
        // 视觉中心偏左；取实际包围盒水平平移，让 ink 几何中心回到 cx。
        path.computeBounds(outerRect, true)
        path.offset(cx - (outerRect.left + outerRect.right) / 2f, 0f)
    }

    /** 供诊断/日志用：格子尺寸的取整，避免日志里出现一长串小数。 */
    fun describe(capH: Float, cellW: Float): String =
        "capH=${max(0f, capH).toInt()} cellW=${max(0f, cellW).toInt()}"
}
