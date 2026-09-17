package com.androiduse.actuation

import com.androiduse.display.VirtualScreen

/**
 * Action → shell 命令字符串。纯函数，可单测。
 *
 * 一律用逻辑 displayId（不是 SurfaceFlinger id），传错 input 会打到错误的屏。
 * Wait / Finish 不产生 shell 命令，返回 null 由调用方处理。
 */
object ActionCommand {

    private const val KEYCODE_HOME = 3
    private const val KEYCODE_BACK = 4

    fun toShell(action: Action, screen: VirtualScreen): String? {
        val d = screen.logicalDisplayId
        return when (action) {
            is Action.Tap -> {
                val x = CoordinateMapper.toPixels(action.xNorm, screen.widthPx)
                val y = CoordinateMapper.toPixels(action.yNorm, screen.heightPx)
                "input -d $d tap $x $y"
            }
            is Action.Swipe -> {
                val x1 = CoordinateMapper.toPixels(action.x1Norm, screen.widthPx)
                val y1 = CoordinateMapper.toPixels(action.y1Norm, screen.heightPx)
                val x2 = CoordinateMapper.toPixels(action.x2Norm, screen.widthPx)
                val y2 = CoordinateMapper.toPixels(action.y2Norm, screen.heightPx)
                "input -d $d swipe $x1 $y1 $x2 $y2 ${action.durationMs}"
            }
            Action.Back -> "input -d $d keyevent $KEYCODE_BACK"
            Action.Home -> "input -d $d keyevent $KEYCODE_HOME"
            is Action.Wait -> null
            is Action.Finish -> null
        }
    }
}
