package it.unipi.studenti.unipingapp.it.unipi.studenti.unipingapp

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import it.unipi.studenti.unipingapp.MainActivity

class AppLocationListener(val appActivity: MainActivity) : LocationListener {

    companion object {
        const val TAG = "LocationListener"
    }

    override fun onLocationChanged(location: Location) {
        var tm = appActivity.getCurrentTimeMark()
        Log.d(TAG, "provider ${location.provider} location.time ${location.time} System.time ${System.currentTimeMillis()}")
        // Rely on NMEA for GPS time
        if (location.provider != LocationManager.GPS_PROVIDER) {
            val candidate = MainActivity.TimeMeasure(
                receivedTimeMs = location.time,
                localMonotonicTM = tm,
                source = location.provider ?: "Unknown"
            )
            appActivity.submitCandidateTime(candidate)
        } else {
            Log.d(TAG, "onLocationChanged received GPS time '${location.time}', but it is not thrust")
        }
    }

    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "onProviderEnabled(\"${provider})\"")
    }
    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "onProviderDisabled(\"${provider})\"")
        appActivity.setLastTimeMeasure(null)
        appActivity.updateTimeSourceText("System")
    }
}