package it.unipi.studenti.unipingapp

import android.location.GnssNavigationMessage
import android.util.Log


class GnssNavigationMessageObserver : GnssNavigationMessage.Callback() {
    companion object {
        const val TAG = "GnssNavigationMessage"
    }

    override fun onGnssNavigationMessageReceived(event: GnssNavigationMessage?) {
        Log.d(TAG, "GnssNavigationMessageReceived: ${event}")
    }

    override fun onStatusChanged(status: Int) {
        Log.d(TAG, "StatusChanged: ${status}")
    }
}