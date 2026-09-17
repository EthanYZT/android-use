package com.androiduse

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.androiduse.databinding.ActivityMainBinding
import com.androiduse.display.VirtualDisplayManager
import com.androiduse.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()

        binding.btnCheckRoot.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { RootShell.exec("id") }
                appendLog("exit=${result.exitCode}\n${result.stdout}${result.stderr}")
            }
        }

        binding.btnCreateScreen.setOnClickListener {
            lifecycleScope.launch {
                val screen = withContext(Dispatchers.IO) {
                    VirtualDisplayManager.create()?.also {
                        VirtualDisplayManager.launchIntentAction("android.settings.SETTINGS", it)
                    }
                }
                appendLog(
                    if (screen == null) "建屏失败"
                    else "建屏成功 logicalId=${screen.logicalDisplayId} sfId=${screen.surfaceFlingerId.toULong()}"
                )
            }
        }

        binding.btnDestroyScreen.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { VirtualDisplayManager.destroy() }
                appendLog("已销毁虚拟屏")
            }
        }
    }

    private fun appendLog(line: String) {
        binding.tvLog.append(line + "\n")
    }
}
