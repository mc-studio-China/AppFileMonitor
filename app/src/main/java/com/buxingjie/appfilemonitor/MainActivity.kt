package com.buxingjie.appfilemonitor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.buxingjie.appfilemonitor.core.I18n
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
    private var updateChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.install(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (I18n.isRtl(this)) {
            window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        }
        setSupportActionBar(binding.toolbar)

        binding.pager.adapter = TabsAdapter(this)
        val navIcons = intArrayOf(
            R.drawable.ic_nav_monitor, R.drawable.ic_nav_files, R.drawable.ic_nav_settings
        )
        val tint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(-android.R.attr.state_selected)),
            intArrayOf(getColor(R.color.brand), getColor(R.color.muted))
        )
        binding.tabs.setTabIconTint(tint)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.icon = getDrawable(navIcons[position])
            tab.text = when (position) {
                0 -> I18n.t(this, "监控")
                1 -> I18n.t(this, "文件")
                else -> I18n.t(this, "设置")
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
        I18n.apply(binding.root)

        // 升级后清掉残留的安装包，避免占空间
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                java.io.File(cacheDir, "update").listFiles()?.forEach { it.delete() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!updateChecked) {
            updateChecked = true
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
