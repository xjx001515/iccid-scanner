package com.iccidscanner.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iccidscanner.app.databinding.ItemRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordAdapter(
    private val onLongClick: (Record) -> Unit,
) : RecyclerView.Adapter<RecordAdapter.Holder>() {

    class Holder(val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<Record> = emptyList()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(records: List<Record>) {
        items = records
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = items[position]
        val source = when (record.source) {
            IccidParser.SOURCE_BARCODE -> "条码"
            IccidParser.SOURCE_OCR -> "文字识别"
            IccidParser.SOURCE_BARCODE_OCR -> "条码+文字"
            else -> "手动"
        }
        with(holder.binding) {
            index.text = (items.size - position).toString()
            iccid.text = IccidParser.pretty(record.iccid)
            meta.text = "${timeFormat.format(Date(record.createdAt))} · $source"
            root.setOnLongClickListener {
                onLongClick(record)
                true
            }
        }
    }
}
