package com.androiduse

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.androiduse.agent.StoredTranscript
import com.androiduse.databinding.ActivityTranscriptBinding
import com.androiduse.log.MarkdownExporter
import com.androiduse.log.TranscriptStore
import com.androiduse.ui.StepAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单个任务的完整日志：头信息 + 每步卡片；菜单"导出"生成 Markdown 走系统分享。 */
class TranscriptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_ID = "taskId"
    }

    private lateinit var binding: ActivityTranscriptBinding
    private val adapter = StepAdapter()
    private var transcript: StoredTranscript? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvSteps.layoutManager = LinearLayoutManager(this)
        binding.rvSteps.adapter = adapter

        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: run { finish(); return }
        lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) { TranscriptStore(File(filesDir, "transcripts")).load(taskId) }
            if (t == null) {
                Toast.makeText(this@TranscriptActivity, R.string.error_transcript_missing, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            transcript = t
            binding.tvTask.text = t.task
            binding.tvMeta.text = "${fmt.format(Date(t.startedAtMs))} · ${t.steps.size} 步 · ${t.model} · ${t.screenW}x${t.screenH}"
            val o = t.outcome
            binding.tvOutcome.text = when {
                o == null -> getString(R.string.outcome_unfinished)
                o.finished -> "✓ ${o.summary}"
                else -> "✗ ${o.summary}"
            }
            adapter.submit(t.steps)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.transcript, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_export -> { export(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun export() {
        val t = transcript ?: return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                File(dir, "${t.taskId}.md").apply { writeText(MarkdownExporter.render(t)) }
            }
            val uri = FileProvider.getUriForFile(this@TranscriptActivity, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/markdown")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "android-use 任务日志 ${t.taskId}")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.action_export)))
        }
    }
}
