package it.unipi.studenti.unipingapp

import android.location.GnssStatus
import android.util.Log

class GnssStatusObserver : GnssStatus.Callback() {
    companion object {
        const val TAG = "GnssStatus"
    }

    override fun onStarted() {
        Log.d(TAG, "Started")
    }

    override fun onStopped() {
        Log.d(TAG, "Stopped")
    }

    override fun onFirstFix(ttffMillis: Int) {
        Log.d(TAG, "FirstFix: ${ttffMillis}")
    }

    override fun onSatelliteStatusChanged(status: GnssStatus) {
        Log.d(TAG, "SatelliteStatusChanged: ${status}")
    }
}
