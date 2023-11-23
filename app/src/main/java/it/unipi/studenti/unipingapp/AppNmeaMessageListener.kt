package it.unipi.studenti.unipingapp.it.unipi.studenti.unipingapp

import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.util.Log
import it.unipi.studenti.unipingapp.MainActivity
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AppNmeaMessageListener(val appActivity: MainActivity) : OnNmeaMessageListener {
    override fun onNmeaMessage(message: String?, timestamp: Long) {
        var tm = appActivity.getCurrentTimeMark()
        Log.d("GPS", "onNmeaMessage msg ${message} ts ${timestamp} System.time ${System.currentTimeMillis()}")
        // consider only time in GPGGA sentences
        // Reference: https://www.rfwireless-world.com/Terminology/GPS-sentences-or-NMEA-sentences.html
        // Reference: https://receiverhelp.trimble.com/alloy-gnss/en-us/NMEA-0183messages_GNS.html
        // NMEA packets could be artifically generated, then missing some fields:
        //  https://stackoverflow.com/questions/63917764/gpgga-and-gprmc-sentences-are-not-received-in-onnmeamessage
//        if (message != null && (message.startsWith("\$GPGGA") || message.startsWith("\$GPRMC") || message.startsWith("\$GNGNS"))) {
        if (message != null && message.startsWith("\$") && message.startsWith("GGA", 3)) {
            try {
                // UTC time is second field in sentence
                val utcTimeNow = OffsetDateTime.now(ZoneOffset.UTC)
                // hhmmss.sss
                val utc = message.split(',')[1].split('.')
                val ms = utc[1].let {
                    when (it.length) {
                        0 -> 0
                        1 -> it.toInt() * 100
                        2 -> it.toInt() * 10
                        3 -> it.toInt()
                        else -> it.substring(0..2).toInt()
                    }
                }
                val seconds_since_midnight = utc[0].let {
                    ( ( (
                            it.substring(0..1).toInt() * 60
                            ) + it.substring(2..3).toInt() ) * 60
                            + it.substring(4..5).toInt()
                            )
                }
                val midnigth =  utcTimeNow.let {
                    OffsetDateTime.of(
                        it.year, it.monthValue, it.dayOfMonth,
                        0, 0, 0, 0,
                        it.offset)
                }
                // convert obtained time to timestamp
                val calculatedTs = midnigth
                    .plusSeconds(seconds_since_midnight.toLong())
                    .toEpochSecond() * 1000L + ms.toLong()

                if (appActivity.nmeaDebug) {
                    appActivity.sendDebugMsg("NMEA parsed TS ${calculatedTs} from ${message}")
                }
                val candidate = MainActivity.TimeMeasure(
                    receivedTimeMs = calculatedTs,
                    localMonotonicTM = tm,
                    source = LocationManager.GPS_PROVIDER
                )
                appActivity.submitCandidateTime(candidate)
            } catch (e: Exception) {
                Log.e("NMEA", "Exception while decoding UTC time in ${message}")
            }
        }
        if (appActivity.nmeaDebug && message != null) {
            appActivity.debugNmea(message)
        }
    }
}