/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.get
import com.google.android.material.tabs.TabLayout
import kotlinx.android.synthetic.main.fragment_tabstray.*
import mozilla.components.feature.tabs.tabstray.TabsFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.browser.BrowserFragment
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.components.Container

/**
 * A fragment for displaying the tabs tray.
 */
class TabsTrayFragment : Fragment(), UserInteractionHandler, TabLayout.BaseOnTabSelectedListener<TabLayout.Tab> {
    private var tabsFeature: TabsFeature? = null
    private var containers = Container.default()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_tabstray, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        containers.forEach {
            val icon = resources.getDrawable(it.icon, null)
            icon.setTint(ContextCompat.getColor(requireContext(), it.color))
            tabLayout.addTab(
                    tabLayout.newTab()
                            .setIcon(icon)
                            .setTag(it)
            )
        }

        tabLayout.addOnTabSelectedListener(this)

        tabsFeature = TabsFeature(
            tabsTray,
            requireComponents.core.store,
            requireComponents.useCases.tabsUseCases,
            {
                val container = containers[tabLayout.selectedTabPosition]
                it.contextId == container.name
            },
            ::closeTabsTray)

        tabsPanel.initialize(
                tabsFeature,
                { closeTabsTray() },
                { containers[tabLayout.selectedTabPosition] }
        )
    }

    override fun onStart() {
        super.onStart()

        tabsFeature?.start()
    }

    override fun onStop() {
        super.onStop()

        tabsFeature?.stop()
    }

    override fun onBackPressed(): Boolean {
        closeTabsTray()
        return true
    }

    private fun closeTabsTray() {
        activity?.supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, BrowserFragment.create())
            commit()
        }
    }

    override fun onTabReselected(tab: TabLayout.Tab?) {

    }

    override fun onTabUnselected(tab: TabLayout.Tab?) {

    }

    override fun onTabSelected(tab: TabLayout.Tab?) {
        tabsFeature?.filterTabs {
            val container = tab?.tag as Container
            it.contextId == container.name
        }
    }
}
