package com.androiduse

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.androiduse.databinding.ActivitySettingsBinding
import com.androiduse.perception.ScreenCapture
import com.androiduse.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 配置信息 + 调试按钮（检查 Root / 建屏并打开设置 / 销屏 / 截图预览）。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tvConfig.text = buildString {
            append("模型   ").append(BuildConfig.ARK_MODEL_ID.ifBlank { "（未配置）" }).append('\n')
            append("端点   ").append(BuildConfig.ARK_BASE_URL.ifBlank { "（未配置）" }).append('\n')
            append("密钥   ").append(if (BuildConfig.ARK_API_KEY.isBlank()) "（未配置）" else "已配置").append('\n')
            append("日志   ").append(File(filesDir, "transcripts").absolutePath)
        }

        binding.btnCheckRoot.setOnClickListener {
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { RootShell.exec("id") }
                log("exit=${r.exitCode}\n${r.stdout}${r.stderr}".trim())
            }
        }

        binding.btnCreateScreen.setOnClickListener {
            lifecycleScope.launch {
                setButtonsEnabled(false)
                try {
                    val existed = ScreenSession.screen != null
                    val s = withContext(Dispatchers.IO) { ScreenSession.ensure() }
                    log(
                        when {
                            s == null -> "建屏失败"
                            existed -> "已有虚拟屏 logicalId=${s.logicalDisplayId}"
                            else -> "建屏成功 logicalId=${s.logicalDisplayId} sfId=${s.surfaceFlingerId.toULong()}，已打开设置"
                        }
                    )
                } finally {
                    setButtonsEnabled(true)
                }
            }
        }

        binding.btnDestroyScreen.setOnClickListener {
            lifecycleScope.launch {
                setButtonsEnabled(false)
                try {
                    val ok = withContext(Dispatchers.IO) { ScreenSession.destroy() }
                    log(if (ok) "已销毁虚拟屏" else "销毁虚拟屏失败")
                } finally {
                    setButtonsEnabled(true)
                }
            }
        }

        binding.btnCapture.setOnClickListener {
            val screen = ScreenSession.screen
            if (screen == null) { log("还没建屏"); return@setOnClickListener }
            lifecycleScope.launch {
                setButtonsEnabled(false)
                try {
                    val t0 = System.currentTimeMillis()
                    val bmp = withContext(Dispatchers.IO) { ScreenCapture.capture(screen) }
                    val cost = System.currentTimeMillis() - t0
                    if (bmp == null) {
                        log("截图失败")
                    } else {
                        binding.ivPreview.setImageBitmap(bmp)
                        binding.ivPreview.visibility = View.VISIBLE
                        log("截图成功 ${bmp.width}x${bmp.height} 耗时 ${cost}ms")
                    }
                } finally {
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnCreateScreen.isEnabled = enabled
        binding.btnDestroyScreen.isEnabled = enabled
        binding.btnCapture.isEnabled = enabled
    }

    private fun log(line: String) {
        binding.tvDebugLog.append(line + "\n")
    }
}
