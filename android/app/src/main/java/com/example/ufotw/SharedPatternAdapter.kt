package com.first.ufotw

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.first.ufotw.databinding.ItemSharedPatternBinding

class SharedPatternAdapter : RecyclerView.Adapter<SharedPatternAdapter.VH>() {

    private var items: List<SharedPattern> = emptyList()
    private var visible: List<SharedPattern> = emptyList()
    private var currentFilter: String = ""

    var onTryClick: ((SharedPattern) -> Unit)? = null

    // Playhead state
    private var playingId: String? = null
    private var playingProgress: Float = 0f
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    /**
     * Update the playhead. Pass id=null to clear any active playhead.
     * Directly updates visible holders without notifyItemChanged to avoid rebind flicker.
     */
    fun setPlayhead(id: String?, progress: Float) {
        val prevId = playingId
        playingId = id
        playingProgress = progress

        val rv = attachedRecyclerView ?: return

        // Clear the previously playing holder if it changed
        if (prevId != null && prevId != id) {
            val prevIndex = visible.indexOfFirst { it.id == prevId }
            if (prevIndex >= 0) {
                (rv.findViewHolderForAdapterPosition(prevIndex) as? VH)?.clearPlayhead()
            }
        }

        // Update the currently playing holder
        if (id != null) {
            val index = visible.indexOfFirst { it.id == id }
            if (index >= 0) {
                (rv.findViewHolderForAdapterPosition(index) as? VH)?.setPlayhead(progress)
            }
        }
    }

    fun submit(newItems: List<SharedPattern>) {
        items = newItems
        applyFilter(currentFilter)
    }

    fun setFilter(query: String) {
        currentFilter = query
        applyFilter(query)
    }

    private fun applyFilter(query: String) {
        val lower = query.lowercase().trim()
        visible = if (lower.isEmpty()) {
            items
        } else {
            items.filter { sp ->
                sp.title.lowercase().contains(lower) ||
                    sp.author.lowercase().contains(lower) ||
                    sp.tags.joinToString(" ").lowercase().contains(lower)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shared_pattern, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(visible[position])
    }

    override fun getItemCount(): Int = visible.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvAuthor: TextView = itemView.findViewById(R.id.tvAuthor)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvDescription)
        private val tvTags: TextView = itemView.findViewById(R.id.tvTags)
        internal val wavePreview: WaveformPreviewView = itemView.findViewById(R.id.wavePreview)
        private val btnTry: Button = itemView.findViewById(R.id.btnTry)

        fun bind(sp: SharedPattern) {
            tvTitle.text = sp.title
            tvAuthor.text = sp.author

            val loopLabel = if (sp.loop) " · loop" else ""
            tvMeta.text = "${sp.stepCount} steps · ${"%.1f".format(sp.durationSec)}s$loopLabel"

            if (sp.description.isNotEmpty()) {
                tvDescription.text = sp.description
                tvDescription.visibility = View.VISIBLE
            } else {
                tvDescription.visibility = View.GONE
            }

            if (sp.tags.isNotEmpty()) {
                tvTags.text = sp.tags.joinToString(" ") { "#$it" }
                tvTags.visibility = View.VISIBLE
            } else {
                tvTags.visibility = View.GONE
            }

            wavePreview.steps = sp.steps
            wavePreview.loop = sp.loop

            // Restore playhead state after recycling
            wavePreview.playheadProgress = if (sp.id == playingId) playingProgress else null

            btnTry.setOnClickListener { onTryClick?.invoke(sp) }
        }

        fun setPlayhead(progress: Float) {
            wavePreview.playheadProgress = progress
        }

        fun clearPlayhead() {
            wavePreview.playheadProgress = null
        }
    }
}
