package com.androiduse.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.androiduse.agent.StoredTranscript
import com.androiduse.databinding.ItemTaskBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史任务列表项：任务文本、时间/步数/模型、结果一行。 */
class TaskAdapter(private val onClick: (StoredTranscript) -> Unit) : RecyclerView.Adapter<TaskAdapter.VH>() {

    private val items = ArrayList<StoredTranscript>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun submit(list: List<StoredTranscript>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemTaskBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val b = holder.b
        b.tvTask.text = t.task
        b.tvMeta.text = "${fmt.format(Date(t.startedAtMs))} · ${t.steps.size} 步 · ${t.model}"
        val o = t.outcome
        b.tvOutcome.text = when {
            o == null -> "… 未结束"
            o.finished -> "✓ ${o.summary}"
            else -> "✗ ${o.summary}"
        }
        b.root.setOnClickListener { onClick(t) }
    }
}
