package org.mozilla.reference.browser.sessions

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.CoroutineContext

data class SnapshotEntity(val id: Long, val savedAt: Long, val snapshot: SessionManager.Snapshot) {
    val formattedSavedAt by lazy {
        val isSameDay: (Calendar, Calendar) -> Boolean = { a, b ->
            a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
                    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        }

        val parse: (Date) -> String = { date ->
            val dateCal = Calendar.getInstance().apply { time = date }
            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

            val time = time.format(date)
            val month = month.format(date)
            val day = day.format(date)
            val dayOfWeek = dayOfWeek.format(date)


            when {
                isSameDay(dateCal, today) -> "Today @ $time"
                isSameDay(dateCal, yesterday) -> "Yesterday @ $time"
                else -> "$dayOfWeek $month/$day @ $time"
            }
        }


       parse(Date(savedAt))
    }

    private companion object {
        val time = SimpleDateFormat("h:mm a", Locale.US)
        val month = SimpleDateFormat("M", Locale.US)
        val day = SimpleDateFormat("d", Locale.US)
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.US)
    }
}

class SessionListViewModel(engine: Engine): ViewModel() {
    private var sessionBundles = MutableLiveData<List<SessionBundle>>()

    val snapshotEntities: LiveData<List<SnapshotEntity>> = map(sessionBundles) { bundles ->
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

    fun updateData(bundles: List<SessionBundle>) {
        sessionBundles.value = bundles
    }
}

class SessionListFragment : Fragment(), CoroutineScope {
    sealed class InteractionEvent {
        object Search: InteractionEvent()
        data class Session(val snapshot: SnapshotEntity): InteractionEvent()
    }

    private var job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    private lateinit var viewModel: SessionListViewModel
    var onInteractionEvent: ((InteractionEvent) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_session_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val sessionsAdapter = SessionsAdapter() {
            Log.e("Session Tapped!", it.id.toString())
            onInteractionEvent?.invoke(InteractionEvent.Session(it))
        }

        toolbar.setOnClickListener { _ ->
            onInteractionEvent?.invoke(InteractionEvent.Search)
        }

        viewModel.snapshotEntities.observe(this, Observer { snapshots ->
            session_list.visibility = if (!snapshots.isEmpty()) { View.VISIBLE } else { View.GONE }
            no_sessions.visibility = if (snapshots.isEmpty()) { View.VISIBLE } else { View.GONE }

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

private class SessionViewHolder(itemView: View, val onTap: (SnapshotEntity) -> Unit) : RecyclerView.ViewHolder(itemView) {

    private val timestamp: TextView = itemView.findViewById(R.id.timestamp)
    private val titles: TextView = itemView.findViewById(R.id.site_titles)
    private val overflow: TextView = itemView.findViewById(R.id.title_overflow)
    private var item: SnapshotEntity? = null

    init {
        itemView.setOnClickListener { _ ->
            val item = this.item ?: return@setOnClickListener
            onTap(item)
        }
    }

    fun bind(item: SnapshotEntity) {
        this.item = item

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
            else -> "+${extras.size} more sites..."
        }

    }


    companion object {
        const val LAYOUT_ID = R.layout.session_layout
    }
}
private class SessionsAdapter(private val onTap: (SnapshotEntity) -> Unit) :
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
                LayoutInflater.from(parent.context).inflate(SessionViewHolder.LAYOUT_ID, parent, false), onTap)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val item = entities[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = entities.size
}