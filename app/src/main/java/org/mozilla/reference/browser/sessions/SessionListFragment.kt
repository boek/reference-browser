package org.mozilla.reference.browser.sessions

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.Transformations.map
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.fragment_session_list.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import mozilla.components.browser.session.SessionManager
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.session.bundling.SessionBundle

import org.mozilla.reference.browser.R
import java.lang.Exception
import java.net.URL
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext

@RequiresApi(Build.VERSION_CODES.O)
data class SnapshotEntity(val id: Long, val savedAt: Long, val snapshot: SessionManager.Snapshot) {
    val formattedSavedAt by lazy {
        val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
        val dateTime = Instant.ofEpochMilli(savedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val date = dateTime.toLocalDate()
        val todayDate = LocalDate.now()
        val yesterdayDate = todayDate.minusDays(1)

        when (date) {
            todayDate -> "Today @ ${timeFormatter.format(dateTime)}"
            yesterdayDate -> "Yesterday @ ${timeFormatter.format(dateTime)}"
            else -> "${dateTime.dayOfWeek} ${dateTime.monthValue}/${dateTime.dayOfMonth} @ ${timeFormatter.format(dateTime)}"
        }
    }
}

class SessionListViewModel(snapshots: LiveData<List<SessionBundle>>, engine: Engine): ViewModel() {
    val snapshots: LiveData<List<SnapshotEntity>> = map(snapshots) { bundles ->
        bundles.mapNotNull { bundle ->
            val savedAtField = bundle.javaClass.getDeclaredField("savedAt")
            savedAtField .isAccessible = true
            val savedAt = savedAtField.get(bundle) as Long

            val idField = bundle.javaClass.getDeclaredField("id")
            idField.isAccessible = true
            val id = idField.get(bundle) as Long

            val snapshot = bundle.restoreSnapshot(engine) ?: return@mapNotNull null

            SnapshotEntity(id, savedAt, snapshot)
        }.sortedByDescending { it.savedAt }
    }
}

class SessionListFragment : Fragment(), CoroutineScope {
    private var job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    private lateinit var viewModel: SessionListViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_session_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val sessionsAdapter = SessionsAdapter(requireContext())

        viewModel.snapshots.observe(this, Observer { snapshots ->
            launch(IO) {
                sessionsAdapter.refresh(snapshots)
            }
        })
//            sessionsAdapter.submitList(pagedList) })

        session_list.apply {
            adapter = sessionsAdapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }
    }

    companion object {
        fun create(viewModel: SessionListViewModel) = SessionListFragment().apply {
            this.viewModel = viewModel
        }
    }
}

private class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val timestamp: TextView = itemView.findViewById(R.id.timestamp)
    private val titles: TextView = itemView.findViewById(R.id.site_titles)
    private val overflow: TextView = itemView.findViewById(R.id.title_overflow)

    fun bind(item: SnapshotEntity) {
        timestamp.text = item.formattedSavedAt

        val urlFormatter: (String) -> String = { url ->
            try {
                URL(url).host
            } catch (e: Exception) {
                url
            }
        }
        val titles = item?.snapshot?.sessions
                ?.map { urlFormatter(it.session.url) } ?: listOf()

        val mainTitles = titles.take(3)
        val extras = titles.drop(3)

        this.titles.text = mainTitles.joinToString(", ")

        overflow.text = when(extras.count()) {
            0 -> ""
            1 -> "1 more site"
            else -> "+#{extras.count} more sites..."
        }

    }


    companion object {
        const val LAYOUT_ID = R.layout.session_layout
    }
}
private class SessionsAdapter(private val context: Context) :
        RecyclerView.Adapter<SessionViewHolder>() {
    inner class DiffCallback(
            private val oldSnapshots: List<SnapshotEntity>,
            private val newSnapshots: List<SnapshotEntity>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldSnapshots.size
        override fun getNewListSize(): Int = newSnapshots.size
        override fun areItemsTheSame(p0: Int, p1: Int): Boolean = true
        override fun areContentsTheSame(p0: Int, p1: Int): Boolean =
                oldSnapshots[p0].id == newSnapshots[p1].id
        override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? =
                newSnapshots[newItemPosition]
    }

    private var entities: List<SnapshotEntity> = listOf()
    private var pendingJob: Job? = null

    suspend fun refresh(snapshots: List<SnapshotEntity>) = coroutineScope {
        pendingJob?.cancel()

        pendingJob = launch(IO) {
            val result = DiffUtil.calculateDiff(DiffCallback(this@SessionsAdapter.entities, snapshots))

            launch(Main) {
                result.dispatchUpdatesTo(this@SessionsAdapter)
                this@SessionsAdapter.entities = snapshots
            }
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        return SessionViewHolder(
                LayoutInflater.from(parent.context).inflate(SessionViewHolder.LAYOUT_ID, parent, false))
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val item = entities[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = entities.size
}