package nodomain.freeyourgadget.gadgetbridge.activities

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.BondingUtil
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

/** Owns system consent without interrupting the existing device connection. */
@RequiresApi(Build.VERSION_CODES.O)
class CompanionPairingFragment : Fragment() {
    private val address: String get() = requireArguments().getString(ARG_ADDRESS)!!
    private var requested = false

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val manager = BondingUtil.getCompanionDeviceManager(requireContext())
            if (manager?.associations?.any { it.equals(address, ignoreCase = true) } == true) {
                BondingUtil.StartObserving(requireContext(), address)
                GB.toast(getString(R.string.companion_pairing_bound), Toast.LENGTH_SHORT, GB.INFO)
            } else {
                showFailure()
            }
        } else if (result.resultCode != Activity.RESULT_CANCELED) {
            showFailure()
        }
        close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean(STATE_REQUESTED) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REQUESTED, requested)
    }

    override fun onStart() {
        super.onStart()
        if (requested) return
        requested = true
        val context = requireContext().applicationContext
        val targetAddress = address
        try {
            val manager = BondingUtil.getCompanionDeviceManager(context)
                ?: throw IllegalStateException("Companion device service unavailable")
            if (manager.associations.any { it.equals(targetAddress, ignoreCase = true) }) {
                BondingUtil.StartObserving(context, targetAddress)
                GB.toast(getString(R.string.companion_pairing_bound), Toast.LENGTH_SHORT, GB.INFO)
                close()
                return
            }
            val request = AssociationRequest.Builder()
                .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(targetAddress).build())
                .setSingleDevice(true)
                .build()
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    onAssociationPending(chooserLauncher)
                }

                override fun onAssociationPending(chooserLauncher: IntentSender) {
                    if (!isAdded) return
                    try {
                        consentLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                    } catch (e: Exception) {
                        LOG.warn("Unable to open companion consent", e)
                        showFailure()
                        close()
                    }
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    BondingUtil.StartObserving(context, targetAddress)
                }

                override fun onFailure(error: CharSequence?) {
                    LOG.warn("Companion association failed: {}", error)
                    if (!isAdded) return
                    showFailure()
                    close()
                }
            }, null)
        } catch (e: Exception) {
            LOG.warn("Unable to request companion association", e)
            showFailure()
            close()
        }
    }

    private fun showFailure() {
        GB.toast(getString(R.string.companion_pairing_failed), Toast.LENGTH_LONG, GB.ERROR)
    }

    private fun close() {
        parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(CompanionPairingFragment::class.java)
        private const val TAG = "companion_pairing"
        private const val ARG_ADDRESS = "address"
        private const val STATE_REQUESTED = "requested"

        @JvmStatic
        fun start(activity: FragmentActivity, address: String) {
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            val fragment = CompanionPairingFragment().apply {
                arguments = Bundle().apply { putString(ARG_ADDRESS, address) }
            }
            manager.beginTransaction().add(fragment, TAG).commit()
        }
    }
}
