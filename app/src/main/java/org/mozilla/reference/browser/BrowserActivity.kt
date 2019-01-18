/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import androidx.appcompat.app.AppCompatActivity
import android.util.AttributeSet
import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import mozilla.components.browser.session.Session
import mozilla.components.browser.tabstray.BrowserTabsTray
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.tabstray.TabsTray
import mozilla.components.feature.intent.IntentProcessor
import mozilla.components.lib.crash.Crash
import mozilla.components.support.utils.SafeIntent
import org.mozilla.reference.browser.R.string.*
import org.mozilla.reference.browser.browser.BrowserFragment
import org.mozilla.reference.browser.browser.CrashIntegration
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.isCrashReportActive
import org.mozilla.reference.browser.sessions.SessionListFragment
import org.mozilla.reference.browser.sessions.SessionListFragment.InteractionEvent
import org.mozilla.reference.browser.sessions.SessionListViewModel
import org.mozilla.reference.browser.telemetry.DataReportingNotification

open class BrowserActivity : AppCompatActivity(), ComponentCallbacks2 {

    private lateinit var crashIntegration: CrashIntegration
    private lateinit var sessionsViewModel: SessionListViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionsViewModel = SessionListViewModel(components.core.engine)
        val snapshots = components.core.storage.bundles(40)

        snapshots.observe(this, Observer {
            sessionsViewModel.updateData(it)
        })

        if (savedInstanceState == null) {
            presentSessionList()
        }

        if (isCrashReportActive) {
            crashIntegration = CrashIntegration(this, components.analytics.crashReporter) { crash ->
                onNonFatalCrash(crash)
            }
            lifecycle.addObserver(crashIntegration)
        }

        DataReportingNotification.checkAndNotifyPolicy(this)
    }

    override fun onBackPressed() {
        supportFragmentManager.fragments.forEach {
            if (it is BackHandler && it.onBackPressed()) {
                return
            }
        }

        super.onBackPressed()
    }

    override fun onCreateView(parent: View?, name: String?, context: Context, attrs: AttributeSet?): View? =
        when (name) {
            EngineView::class.java.name -> components.core.engine.createView(context, attrs).asView()
            TabsTray::class.java.name -> BrowserTabsTray(context, attrs)
            else -> super.onCreateView(parent, name, context, attrs)
        }

    override fun onTrimMemory(level: Int) {
        components.core.sessionManager.onLowMemory()
    }

    private fun onNonFatalCrash(crash: Crash) {
        Snackbar.make(findViewById(android.R.id.content), crash_report_non_fatal_message, LENGTH_LONG)
            .setAction(crash_report_non_fatal_action) { _ ->
                crashIntegration.sendCrashReport(crash)
            }.show()
    }

    private fun presentSessionList() {
        window.statusBarColor = resources.getColor(R.color.home_bg)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val sessionListFragment = SessionListFragment.create(sessionsViewModel)
        sessionListFragment.onInteractionEvent = {
            components.core.sessionManager.removeAll()
            when (it) {
                is InteractionEvent.Search -> {
                    components.core.sessionManager.add(Session("about:blank"))
                    val fragment = presentBrowser()
                    fragment.presentSearch()
                }
                is InteractionEvent.Session -> {
                    components.core.sessionManager.restore(it.snapshot.snapshot)
                    presentBrowser()
                }
            }
        }

        supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, sessionListFragment)
            commit()
        }
    }

    fun archiveSession() {
        components.core.storage.save(components.core.sessionManager.createSnapshot()!!)
        endSession()
    }

    fun endSession() {
        components.core.sessionManager.removeAll()
        presentSessionList()
    }

    private fun presentBrowser(): BrowserFragment {
        window.statusBarColor = resources.getColor(R.color.primary)
        window.decorView.systemUiVisibility = 0

        val sessionId = SafeIntent(intent).getStringExtra(IntentProcessor.ACTIVE_SESSION_ID)
        val fragment = BrowserFragment.create(sessionId)
        supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, fragment)
            commit()
        }

        return fragment
    }
}
