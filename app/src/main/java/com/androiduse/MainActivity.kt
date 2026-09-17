package com.androiduse

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.androiduse.databinding.ActivityMainBinding
import com.androiduse.display.VirtualDisplayManager
import com.androiduse.display.VirtualScreen
import com.androiduse.perception.ScreenCapture
import com.androiduse.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentScreen: VirtualScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ScreenCapture.cacheDir = cacheDir

        binding.tvLog.movementMethod = ScrollingMovementMethod()

        binding.btnCheckRoot.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { RootShell.exec("id") }
                appendLog("exit=${result.exitCode}\n${result.stdout}${result.stderr}")
            }
        }

        binding.btnCreateScreen.setOnClickListener {
            lifecycleScope.launch {
                setScreenButtonsEnabled(false)
                try {
                    val screen = withContext(Dispatchers.IO) { VirtualDisplayManager.create() }
                    if (screen == null) {
                        appendLog("建屏失败")
                    } else {
                        val launched = withContext(Dispatchers.IO) {
                            VirtualDisplayManager.launchIntentAction("android.settings.SETTINGS", screen)
                        }
                        currentScreen = screen
                        appendLog(
                            "建屏成功 logicalId=${screen.logicalDisplayId} sfId=${screen.surfaceFlingerId.toULong()} " +
                                "启动=${if (launched) "成功" else "失败"}"
                        )
                    }
                } finally {
                    setScreenButtonsEnabled(true)
                }
            }
        }

        binding.btnDestroyScreen.setOnClickListener {
            lifecycleScope.launch {
                setScreenButtonsEnabled(false)
                try {
                    val destroyed = withContext(Dispatchers.IO) { VirtualDisplayManager.destroy() }
                    if (destroyed) currentScreen = null
                    appendLog(if (destroyed) "已销毁虚拟屏" else "销毁虚拟屏失败")
                } finally {
                    setScreenButtonsEnabled(true)
                }
            }
        }

        binding.btnCapture.setOnClickListener {
            val screen = currentScreen
            if (screen == null) {
                appendLog("还没建屏")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                setScreenButtonsEnabled(false)
                try {
                    val t0 = System.currentTimeMillis()
                    val bmp = withContext(Dispatchers.IO) { ScreenCapture.capture(screen) }
                    val cost = System.currentTimeMillis() - t0
                    if (bmp == null) {
                        appendLog("截图失败")
                    } else {
                        binding.ivPreview.setImageBitmap(bmp)
                        appendLog("截图成功 ${bmp.width}x${bmp.height} 耗时 ${cost}ms")
                    }
                } finally {
                    setScreenButtonsEnabled(true)
                }
            }
        }
    }

    /** 建屏/销屏/截图操作进行中禁用相关按钮，避免用户并发点击触发重叠调用。 */
    private fun setScreenButtonsEnabled(enabled: Boolean) {
        binding.btnCreateScreen.isEnabled = enabled
        binding.btnDestroyScreen.isEnabled = enabled
        binding.btnCapture.isEnabled = enabled
    }

    private fun appendLog(line: String) {
        binding.tvLog.append(line + "\n")
    }
}
