package com.androiduse.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.androiduse.agent.StoredStep
import com.androiduse.databinding.ItemStepBinding
import java.io.File

/** 一步一张卡：笔记是主文本，动作是等宽行，结果一行，元信息一行，右侧截图缩略图。 */
class StepAdapter : RecyclerView.Adapter<StepAdapter.VH>() {

    private val items = ArrayList<StoredStep>()

    fun submit(list: List<StoredStep>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(step: StoredStep) {
        items.add(step)
        notifyItemInserted(items.size - 1)
    }

    class VH(val b: ItemStepBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStepBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val b = holder.b
        val last = s.replies.lastOrNull()
        b.tvStepIndex.text = "#${s.index}"
        b.tvNote.text = last?.note?.ifBlank { "（无笔记）" } ?: "（无回复）"
        val calls = last?.toolCalls.orEmpty()
        b.tvAction.text = if (calls.isEmpty()) "—" else calls.joinToString("; ") { "${it.name} ${it.argumentsJson}" }
        val e = s.execution
        b.tvResult.text = when {
            e == null -> "…"
            e.ok -> "✓ ${e.result}"
            else -> "✗ ${e.result}"
        }
        b.tvMeta.text = listOfNotNull(
            "节点 ${s.nodeCount}",
            s.dumpError?.let { "读取失败" },
            last?.reasoningTokens?.let { "推理 $it tok" },
            last?.let { "%.1f s".format(it.latencyMs / 1000.0) },
            e?.let { "执行 ${it.costMs} ms" },
        ).joinToString(" · ")

        val path = s.screenshotPath
        if (path != null && File(path).isFile) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bmp = BitmapFactory.decodeFile(path, opts)
            if (bmp != null) {
                b.ivShot.setImageBitmap(bmp)
                b.ivShot.visibility = View.VISIBLE
                return
            }
        }
        b.ivShot.setImageDrawable(null)
        b.ivShot.visibility = View.GONE
    }
}
