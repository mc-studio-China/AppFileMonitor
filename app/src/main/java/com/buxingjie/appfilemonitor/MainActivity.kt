package com.buxingjie.appfilemonitor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.buxingjie.appfilemonitor.core.MonitorWorker
import com.buxingjie.appfilemonitor.core.Prefs
import com.buxingjie.appfilemonitor.core.Shell
import com.buxingjie.appfilemonitor.databinding.ActivityMainBinding
import com.buxingjie.appfilemonitor.ui.FilesFragment
import com.buxingjie.appfilemonitor.ui.MonitorFragment
import com.buxingjie.appfilemonitor.ui.SettingsFragment
import com.buxingjie.appfilemonitor.ui.UpdatePrompter
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.pager.adapter = TabsAdapter(this)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> "监控"
                1 -> "文件"
                else -> "设置"
            }
        }.attach()

        val ctx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            Shell.awaitBinder(2000)
            Shell.refresh(ctx)
        }
        if (Prefs.monitored(ctx).isNotEmpty()) {
            MonitorWorker.schedule(ctx)
        }
        if (savedInstanceState == null) {
            UpdatePrompter(this).check(auto = true)
        }
    }

    private class TabsAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> MonitorFragment()
            1 -> FilesFragment()
            else -> SettingsFragment()
        }
    }
}
