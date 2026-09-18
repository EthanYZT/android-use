package com.androiduse.display

/**
 * 守护进程持有的不可见虚拟屏（spec 1e）。逻辑 displayId 用于 `am start --display` / `input -d` / dump。
 * 截图不再需要 SurfaceFlinger id——帧直接向守护进程要（[com.androiduse.perception.ScreenCapture]）。
 */
data class VirtualScreen(val logicalDisplayId: Int, val widthPx: Int, val heightPx: Int)
