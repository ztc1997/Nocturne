package com.ztc1997.nocturne.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import com.ztc1997.nocturne.core.Placeholder
import kotlin.math.max
import kotlin.math.min

/**
 * 星月自绘 span（方案 2，2026-11-17；同日二次修订）。
 *
 * 背景：时钟专用字体只覆盖数字/冒号，☾/✦ 走字体回退后（a）垂直位置随各面
 * 时钟字体的度量上下漂移——桌面偏下、AOD/锁屏偏上；（b）☾ 与 ✦ 是两个独立
 * 设计的 Unicode 字形，同一串里高度不一致。字体层面无解，改为自绘：
 *
 * - [getSize] 不修改行度量（fm 保持 paint 原值），布局行高与真实数字完全一致；
 * - 字形高度 = 该 paint 下数字 "0" 的 ink 高（cap band），与本地真实数字等高；
 * - **单元格宽度 = 被替换数字自身的 advance**（二次修订核心）：每个 span 携带
 *   原数字字符，宽度取 paint.measureText(原数字)。此前统一用 "0" 的宽度，
 *   遇到窄数字（如 "1"）整串比真实时间宽，锁屏→桌面过场动画按真实度量收敛，
 *   动画结束落点一比即"跳一下"（真机实测）。宽度逐位继承后，占位串与真实
 *   数字串度量完全一致，任何对真实数字无缝的动画对占位符同样无缝；
 * - 星 = 实芯四芒星（与模块应用图标一致的四芒样式），月 = 实芯月牙（Path
 *   DIFFERENCE 挖出），填充色取 paint.color，随状态栏 tint 等自动适配；
 *   窄单元格内字形按 cell 等比压横向，不与邻位重叠。
 *
 * span 逐字符挂在 ☾/✦ 上；若宿主链路（如 RedOne 样式化）把文本重建为纯
 * String 丢掉 span，字符仍退回字体回退渲染——功能不丢，只是回到旧观感。
 *
 * 适用范围（2026-11-17 修订）：仅插件时钟（PersonalityClocksFeature）与锁屏
 * SET_TEXT 路径（ClockMaskFeature#writeMask）。状态栏/下拉菜单（getSmallTime
 * RETURN_VALUE）已改为纯字符占位，不再挂本 span。
 */
class StarMoonSpan(private val digit: Char = '0') : ReplacementSpan() {

    private val digitBounds = Rect()
    private val path = Path()
    private val cutPath = Path()
    private val outerRect = RectF()
    private val cutRect = RectF()

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        // 保持行度量与主字体一致（不放大行高、不挪基线上下文）
        if (fm != null) {
            val f = paint.fontMetrics
            fm.top = f.top.toInt()
            fm.ascent = f.ascent.toInt()
            fm.descent = f.descent.toInt()
            fm.bottom = f.bottom.toInt()
            fm.leading = f.leading.toInt()
        }
        return max(cellWidth(paint).toInt(), 1)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        baseline: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val isStar = text[start] == Placeholder.STAR
        val w = cellWidth(paint)
        paint.getTextBounds("0", 0, 1, digitBounds)
        val capH = (digitBounds.bottom - digitBounds.top).toFloat()
        if (capH <= 0f) return
        // 数字 ink band 的垂直中心（digitBounds.top 为负，位于基线上方）
        val cy = baseline + (digitBounds.top + digitBounds.bottom) / 2f
        val cx = x + w / 2f

        val style = paint.style
        paint.style = Paint.Style.FILL
        path.reset()
        if (isStar) {
            drawStar(path, cx, cy, capH, w)
        } else {
            drawMoon(path, cutPath, outerRect, cutRect, cx, cy, capH, w)
        }
        canvas.drawPath(path, paint)
        paint.style = style
    }

    /**
     * 单元格宽度：被替换数字自身的 advance（宽度度量与真实数字逐位一致），
     * 这样占位串总宽 == 真实时间串总宽，过场动画落点不跳。digit 缺省 '0'。
     */
    private fun cellWidth(paint: Paint): Float {
        val w = paint.measureText(digit.toString())
        return if (w > 0f) w else paint.textSize * 0.55f
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
        cutRect.set(
            cutCx - rx, cutCy - ry,
            cutCx + rx, cutCy + ry,
        )
        cutPath.addOval(cutRect, Path.Direction.CW)
        path.op(cutPath, Path.Op.DIFFERENCE)
        cutPath.reset()
        // 挖除后月牙 ink 全在单元格左半边（外椭圆以 cx 居中、挖除圆右偏），
        // 视觉中心偏左；取实际包围盒水平平移，让 ink 几何中心回到 cx。
        // 包围盒宽 < 2rx ≤ 0.8*cellW，平移后仍在单元格内。
        path.computeBounds(outerRect, true)
        path.offset(cx - (outerRect.left + outerRect.right) / 2f, 0f)
    }

    companion object {
        /**
         * 把 [Placeholder.mask] 产出的星月串包成 SpannableString，逐字符挂
         * [StarMoonSpan]。[original] 是被替换的原始文本（与 masked 等长，
         * mask 逐位替换保持长度）：星月位继承原数字的 advance 宽度；
         * original 缺失/长度不符/该位不是数字时退回 '0' 宽度。
         * 没有星月字符时原样返回（零开销直通）。
         */
        fun spanned(masked: String, original: CharSequence? = null): CharSequence {
            if (masked.indexOf(Placeholder.MOON) < 0 && masked.indexOf(Placeholder.STAR) < 0) {
                return masked
            }
            val ss = SpannableString(masked)
            for (i in masked.indices) {
                if (masked[i] == Placeholder.MOON || masked[i] == Placeholder.STAR) {
                    val d = original.getOrNull(i)
                    ss.setSpan(
                        StarMoonSpan(if (d != null && d in '0'..'9') d else '0'),
                        i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            return ss
        }

        private fun CharSequence?.getOrNull(i: Int): Char? =
            if (this != null && i in indices) this[i] else null
    }
}
