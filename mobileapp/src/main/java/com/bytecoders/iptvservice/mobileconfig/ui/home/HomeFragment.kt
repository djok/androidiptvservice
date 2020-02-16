package com.bytecoders.iptvservice.mobileconfig.ui.home

import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.bytecoders.iptvservice.mobileconfig.MainActivityViewModel
import com.bytecoders.iptvservice.mobileconfig.R
import com.bytecoders.iptvservice.mobileconfig.databinding.FragmentHomeBinding
import com.bytecoders.iptvservice.mobileconfig.ui.BaseFragment

class HomeFragment : BaseFragment<HomeViewModel, FragmentHomeBinding>() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.playlist.observe(viewLifecycleOwner, Observer {
            it.epgURL?.let { epg ->
                if (viewBinding.epgUrl.text.toString() != epg) {
                    showNewEPGDialog(epg)
                }
            }
        })
    }

    private fun showNewEPGDialog(epgURL: String) {
        AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.new_epg_url))
                .setMessage(getString(R.string.update_program_guide))
                .setPositiveButton(getString(R.string.ok)) { _, _ -> viewBinding.epgUrl.setText(epgURL) }
                .setNegativeButton(getString(R.string.cancel), null)
                .create().show()

    }

    override fun getLayoutId(): Int = R.layout.fragment_home

    override fun createViewModel(sharedViewModel: MainActivityViewModel): HomeViewModel = ViewModelProvider(this,
            HomeViewModelFactory(PreferenceManager.getDefaultSharedPreferences(requireContext()), sharedViewModel))
            .get(HomeViewModel::class.java)
}