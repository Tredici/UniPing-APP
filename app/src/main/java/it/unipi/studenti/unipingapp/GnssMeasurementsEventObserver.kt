package it.unipi.studenti.unipingapp.it.unipi.studenti.unipingapp

import android.location.GnssMeasurementsEvent
import android.util.Log

class GnssMeasurementsEventObserver : GnssMeasurementsEvent.Callback() {
    companion object {
        const val TAG = "GnssMeasurementsEvent"
    }
    override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent?) {
        Log.d(TAG, "GnssMeasurementsReceived: ${eventArgs}")
    }

    override fun onStatusChanged(status: Int) {
        Log.d(TAG, "onStatusChanged: ${status}")
    }
}
