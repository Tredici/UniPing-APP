@file:OptIn(ExperimentalMaterial3Api::class)

package it.unipi.studenti.unipingapp

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentSkipListMap
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import it.unipi.studenti.unipingapp.it.unipi.studenti.unipingapp.AppLocationListener
import it.unipi.studenti.unipingapp.it.unipi.studenti.unipingapp.AppNmeaMessageListener
import it.unipi.studenti.unipingapp.it.unipi.studenti.unipingapp.GnssMeasurementsEventObserver
import it.unipi.studenti.unipingapp.ui.theme.UniPingAppTheme
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.regex.Pattern
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class MainActivity : ComponentActivity() {

    private val gnssStatusObserver = GnssStatusObserver()
    private val gnssNavigationMessageObserver = GnssNavigationMessageObserver()
    private val gnssMeasurementsEventObserver = GnssMeasurementsEventObserver()

    private val appLocationListener = AppLocationListener(appActivity = this)
    private val appNmeaMessageListener = AppNmeaMessageListener(appActivity = this)

    companion object {
        const val LOCATION_UPDATES_FREQUENCY_MS = 1000L
        const val DEFAULT_PRIORITIZED_TIME_SOURCE = LocationManager.GPS_PROVIDER
        const val SECONDARY_PRIORITIZED_TIME_SOURCE = LocationManager.NETWORK_PROVIDER
        const val MINIMUM_LOC_UP_INTERVAL_MS = 3* LOCATION_UPDATES_FREQUENCY_MS

        // wait 20 before sending next UDPing
        const val INTERSEND_SLEEP_MS = 20L
        const val LAST_SLEEP_MS = 1000L
        const val SEND_REQ = 777
        const val DEBUG_MSG = 888

        val PING_REQ = "PING_REQ".toByteArray()
        val PING_RES = "PING_RES".toByteArray()
        const val PING_REQ_SIZE = 8 + 16 + 4 + 8
        const val PING_RES_SIZE = 8 + 16 + 4 + 8 + 8 + 8

        const val TAG = "PING IP:port test"
        val ipv4Test = Pattern.compile(
            "^([01]?\\d{1,2}|2[0-4]\\d|25[0-5])(\\."
                    +"([01]?\\d{1,2}|2[0-4]\\d|25[0-5])){3}$",
            Pattern.CASE_INSENSITIVE
        ).asPredicate()
        val numberText = Pattern.compile(
            "^\\d+$",
            Pattern.CASE_INSENSITIVE
        ).asPredicate()
        val portTest : (String) -> Boolean = {
            numberText.test(it) && it.toInt() in 1..65535
        }
        val verifyInputs : (String, String) -> Boolean = { ip, port ->
            var ans = true
            if (!ipv4Test.test(ip)) {
                Log.d(TAG, "Invalid ip ${ip}")
                ans = false
            }
            if (!portTest.invoke(port)) {
                Log.d(TAG, "Invalid port ${port}")
                ans = false
            }
            if (ans)
                Log.d(TAG, "Valid remote ${ip}:${port}")
            ans
        }
    }

    data class PingReqParams(val uuid: UUID, val rep: Int, val sendTS: Long)
    // parse and return
    fun parsePingRequest(pkt: DatagramPacket) : PingReqParams? {
        val req: ByteArray = pkt.data
        if (pkt.length != PING_REQ_SIZE) {
            return null
        } else {
            if (!(req.copyOfRange(0, 8) contentEquals PING_REQ)) {
                return  null
            } else {
                // first 8 bytes are
                val bytes = ByteArrayInputStream(req, 8, PING_REQ_SIZE - 8)
                val bs = DataInputStream(bytes)
                val uuid = UUID(bs.readLong(), bs.readLong())
                val rep = bs.readInt()
                val sendTS = bs.readLong()
                return PingReqParams(uuid, rep, sendTS)
            }
        }
    }
    // request generator
    fun generateRequestBody(uuid: UUID, rep: Int, sendTS: Long) : ByteArray {
        val bytes = ByteArrayOutputStream(PING_REQ_SIZE)
        val bs = DataOutputStream(bytes)
        bs.write("PING_REQ".toByteArray())
        bs.writeLong(uuid.mostSignificantBits)
        bs.writeLong(uuid.leastSignificantBits)
        bs.writeInt(rep)
        bs.writeLong(sendTS)
        bs.flush()
        val data = bytes.toByteArray()
        Log.d(TAG, "Built request of ${data.size} bytes")
        return data
    }
    // response generator
    fun generateResponseBody(uuid: UUID, rep: Int, responseSendTS: Long, originalSendTS: Long, originalRecvTS: Long) : ByteArray {
        val bytes = ByteArrayOutputStream(PING_RES_SIZE)
        val bs = DataOutputStream(bytes)
        bs.write("PING_RES".toByteArray())
        bs.writeLong(uuid.mostSignificantBits)
        bs.writeLong(uuid.leastSignificantBits)
        bs.writeInt(rep)
        // time of remote sending the response
        bs.writeLong(responseSendTS)
        // time of local sending the PING request
        bs.writeLong(originalSendTS)
        // time of remote receiving the PING request
        bs.writeLong(originalRecvTS)
        bs.flush()
        return bytes.toByteArray()
    }
    // original* is about PING (request) travel
    data class PingResParams(val uuid: UUID, val rep: Int, val responseSendTS: Long, val requestSendTS: Long, val requestRecvTS: Long)
    fun parsePingResponse(pkt: DatagramPacket) : PingResParams? {
        val res: ByteArray = pkt.data
        if (pkt.length != PING_RES_SIZE) {
            return null
        } else {
        }
        if (!(res.copyOfRange(0, 8) contentEquals PING_RES)) {
            return  null
        } else {
            // first 8 bytes are
            val bytes = ByteArrayInputStream(res, 8, PING_RES_SIZE - 8)
            val bs = DataInputStream(bytes)
            val uuid = UUID(bs.readLong(), bs.readLong())
            val rep = bs.readInt()
            val responseSendTS = bs.readLong()
            val originalSendTS = bs.readLong()
            val originalRecvTS = bs.readLong()
            return PingResParams(
                uuid = uuid,
                rep = rep,
                responseSendTS = responseSendTS,
                requestSendTS = originalSendTS,
                requestRecvTS = originalRecvTS)
        }
    }

    lateinit var udpSocket : DatagramSocket
    lateinit var udpThread : UdpThread
    lateinit var senderThread : HandlerThread
    lateinit var senderHanlder : Handler

    // this thread is charged of listening for
    // incoming UDP packets
    inner class UdpThread : Thread("UDP Thread") {
        private fun handlePingRequest(pkt: DatagramPacket, receiveTS: Long) : Boolean {
            val req = parsePingRequest(pkt)
            if (req != null) {
                val responseSendTS = getCurrentTS()
                val res = generateResponseBody(
                    uuid = req.uuid,
                    rep = req.rep,
                    // remote clock at PING send
                    originalSendTS = req.sendTS,
                    // local clock at receive
                    originalRecvTS = receiveTS,
                    // local clock now
                    responseSendTS = responseSendTS
                )
                val resPkt = DatagramPacket(
                    res, res.size, pkt.address, pkt.port
                )
                udpSocket.send(resPkt)
                Log.d(name, "Sent UDPing response for ${req.uuid} rep ${req.rep} time ${req.sendTS} to ${pkt.address}:${pkt.port} at ${receiveTS}")
                return true
            }
            else return false
        }

        private fun handlePingResponse(pkt: DatagramPacket, receiveTS: Long) : Boolean {
            val res = parsePingResponse(pkt)
            if (res != null) {
                val uuid = res.uuid
                val entry = pingMap[uuid]
                if (entry != null) {
                    Log.d(TAG, "Received response with uuid ${uuid} rep ${res.rep} receiveTS ${receiveTS} remoteSendTS ${res.responseSendTS} remoteReceiveTS ${res.requestRecvTS} originalSend ${res.requestSendTS}")
                    val rep = entry.getRepetitionByNumber(res.rep)
                    if (rep == null) {
                        Log.d(TAG, "Unexpected rep ${res.rep} for uuid ${uuid}")
                    } else if (rep.requestSendTS != res.requestSendTS) {
                        Log.e(TAG, "Send time mismatch in uuid ${uuid} rep ${res.rep} :=> cached ${rep.requestSendTS} received ${res.requestRecvTS}")
                    } else {
                        // TSs obtained by the local machine
                        rep.responseRecvTS = receiveTS
                        // TSs inseted by remote node
                        rep.responseSendTS = res.responseSendTS
                        rep.requestRecvTS = res.requestRecvTS
                    }
                } else {
                    Log.d(TAG, "Unexpected response with uuid ${uuid}")
                }
                return true
            } else {
                return false
            }
        }

        override fun run() {
            val data = ByteArray(1024)
            val pkt = DatagramPacket(data, data.size)
            while (!isInterrupted) {
                try {
                    udpSocket.receive(pkt)
                    val receiveTS = getCurrentTS()
                    val srcIp = pkt.address
                    val srcPort = pkt.port
                    Log.d(name, "Received UDP packet from ${srcIp}:${srcPort} at ${receiveTS} of ${pkt.length} bytes")

                    if (handlePingRequest(pkt, receiveTS)) {
                        Log.d(name, "PING Request handled")
                    } else
                        if (handlePingResponse(pkt, receiveTS)) {
                            Log.d(name, "PING Response handled")
                        } else {
                            Log.d(name, "Packet not recognised")
                        }
                } catch (e: IOException) {
                    if (udpSocket.isClosed) {
                        Log.d(name, "UDP socket closed")
                    } else {
                        Log.e(name, "IO Error on udp socket")
                    }
                    break
                }
            }
        }
    }

    private fun bindSocket(port : String) : Boolean {
        if (portTest.invoke(port)) {
            val p = port.toInt()
            udpSocket = DatagramSocket(p)
            Log.d("UDP Socket", "Bound socket to port ${port}")
            udpThread = UdpThread().apply { start() }
            senderThread = HandlerThread("Sender Thread").apply {
                start()
                senderHanlder = object : Handler(looper) {
                    override fun handleMessage(msg: Message) {
                        super.handleMessage(msg)
                        if (msg?.what == SEND_REQ) {
                            val pingData = msg.obj as PingData
                            if (pingData.expired()) {
                                Log.d(TAG, "End of ping flow: ${pingData.uuid}")
                                // nothing more to do
                                pingData.atEnd()
                            } else {
                                // build packet
                                val now = System.currentTimeMillis()
                                Log.d(name, "Sending msg at ${now}")
                                val sendTS = getCurrentTS()
                                val repData = pingData.nextRepetition(sendTS)
                                val payload = generateRequestBody(pingData.uuid, repData.first, repData.second)
                                val ping = DatagramPacket(payload, payload.size, pingData.ip, pingData.port)
                                udpSocket.send(ping)
                                Log.d(TAG, "Sent PING UUID ${pingData.uuid} rep (${repData.first}+1)/${pingData.reps} at ${repData.second}")
                                if (nmeaDebug) {
                                    sendDebugMsg("PING with send TS ${repData.second}")
                                }
                                // new msg
                                val nMsg = this.obtainMessage(msg.what, msg.obj)
                                this.sendMessageDelayed(nMsg, if (pingData.expired()) LAST_SLEEP_MS else INTERSEND_SLEEP_MS)
                            }
                            true
                        } else if (msg?.what == DEBUG_MSG) {
                            // debug message to be send
                            val dMsg = msg.obj as DebugMsg
                            val txt = dMsg.message
                            val bytes = txt.toByteArray()
                            val udpDebug = DatagramPacket(bytes, bytes.size, dMsg.ip, dMsg.port)
                            udpSocket.send(udpDebug)
                            Log.d("DEBUG", "Sent udp packet")
                        } else { false }
                    }
                }
            }
        }
        return true
    }

    data class PingTSs(
        // local ts before send
        val requestSendTS : Long,
        // TSs inserted by remote
        var requestRecvTS : Long = 0,
        var responseSendTS : Long = 0,
        // local ts at receive
        var responseRecvTS : Long = 0) {
        val requestLatency : Long
            get() = requestRecvTS-requestSendTS
        val responseLatency : Long
            get() = responseRecvTS-responseSendTS
        val rtt : Long
            get() = responseRecvTS - requestSendTS
    }
    data class PingStats(
        val ip: InetAddress, val port: Int,
        val uuid: UUID,
        val reps: Int, var lost: Int = 0,
        var min1: Long = 0, var max1: Long = 0, var mean1: Float = 0f, var var1: Float = 0f,
        var min2: Long = 0, var max2: Long = 0, var mean2: Float = 0f, var var2: Float = 0f,
        var rttMin: Long = 0, var rttMax: Long = 0, var rttMean: Float = 0f, var rttVar: Float = 0f
    )
    class PingData(
        val uuid : UUID,
        val ip : InetAddress,
        val port : Int,
        val reps : Int,
        val atEnd : () -> Unit
    ) {
        // current repetition
        var curr = 0
        // list of scheduled reps
        val tsMap = arrayOfNulls<PingTSs>(reps)

        // return true if no more
        fun expired() : Boolean {
            return curr == reps
        }

        fun nextRepetition(sendTS: Long) : Pair<Int, Long> {
            val idx = curr
            tsMap[idx] = PingTSs(sendTS)
            ++curr
            return Pair(idx, sendTS)
        }

        @OptIn(ExperimentalStdlibApi::class)
        fun getRepetitionByNumber(rep: Int) : PingTSs? {
            if (rep !in 0..<curr) {
                return null
            } else {
                return tsMap[rep]
            }
        }

        fun statistics() : PingStats {
            val ans = PingStats(
                ip = ip,
                port = port,
                uuid = uuid,
                reps = reps)
            var sum1: Long = 0
            var sum2: Long = 0
            var sq_sum1: Long = 0
            var sq_sum2: Long = 0
            var rtt_sum: Long = 0
            var rtt_sq_sum: Long = 0
            for (sample in tsMap) {
                if (sample!!.responseRecvTS == 0L) {
                    ++ans.lost
                } else {
                    if (ans.min1 == 0L || ans.min1 > sample.requestLatency) {
                        ans.min1 = sample.requestLatency
                    }
                    if (ans.min2 == 0L || ans.min2 > sample.responseLatency) {
                        ans.min2 = sample.responseLatency
                    }
                    if (ans.max1 == 0L || ans.max1 < sample.requestLatency) {
                        ans.max1 = sample.requestLatency
                    }
                    if (ans.max2 == 0L || ans.max1 < sample.responseLatency) {
                        ans.max2 = sample.responseLatency
                    }
                    if (ans.rttMax == 0L || ans.rttMax < sample.rtt) {
                        ans.rttMax = sample.rtt
                    }
                    if (ans.rttMin == 0L || ans.rttMin > sample.rtt) {
                        ans.rttMin = sample.rtt
                    }

                    sum1 += sample.requestLatency
                    sq_sum1 += sample.requestLatency*sample.requestLatency
                    sum2 += sample.responseLatency
                    sq_sum2 += sample.responseLatency*sample.responseLatency
                    rtt_sum += sample.rtt
                    rtt_sq_sum += sample.rtt*sample.rtt
                }
            }
            val count = reps-ans.lost
            ans.mean1 = sum1.toFloat() / count.toFloat()
            ans.mean2 = sum2.toFloat() / count.toFloat()
            ans.var1 = (sq_sum1.toDouble() - (sum1 as Long * sum1 as Long / count.toDouble())).toFloat() / count.toFloat()
            ans.var2 = (sq_sum2.toDouble() - (sum2 as Long * sum2 as Long / count.toDouble())).toFloat() / count.toFloat()
            ans.rttMean = rtt_sum.toFloat() / count.toFloat()
            ans.rttVar  = (rtt_sq_sum.toDouble() - (rtt_sum as Long * rtt_sum as Long / count.toDouble())).toFloat() / count.toFloat()
            return ans
        }
    }
    val pingMap = ConcurrentSkipListMap<UUID, PingData>()
    // schedule ping flow on auxiliary thread and results retrieval
    // called on main thread
    private fun schedulePingFlow(
        uuid : UUID,
        ip : InetAddress,
        port : Int,
        reps : Int,
        // callback executed on main thread to report results
        clbck : (PingData) -> Unit
    ) {
        val pd = PingData(uuid, ip, port, reps) {
            val pd = pingMap.remove(uuid)!!
            // callback must run on UI thread
            runOnUiThread {
                Log.d(TAG, "Running update callback")
                clbck(pd)
            }
        }
        pingMap[uuid] = pd
        // after schedule send
        val msg = senderHanlder.obtainMessage(SEND_REQ, pd)
        senderHanlder.sendMessageDelayed(msg, INTERSEND_SLEEP_MS)
    }

    private fun ping(ip : String, port : String, callback : (PingData) -> Unit) {
        Log.d("UDP Socket", "Ping ${ip}:${port}")
        // called on main thread
        // should schedule on separate thread
        // how many repetitions?
        val reps = 15
        // every requests flow is identified by a UUID
        val uuid = UUID.randomUUID()
        // Require SDK 29
        //val dstAddr = InetAddresses.parseNumericAddress(ip)
        val dstAddr = InetAddress.getByName(ip)
        val dstPort = port.toInt()
        schedulePingFlow(uuid, dstAddr, dstPort, reps, callback)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val onBind : (String) -> Boolean = { it -> this.bindSocket(it) }
        initTimeSources()
        setContent {
            UniPingAppTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var pingList: ArrayList<PingStats> by remember {
                        mutableStateOf(ArrayList<PingStats>())
                    }
                    // associated with PRIORITIZED_TIME_SOURCE
                    var isPreferGps : Boolean by remember { mutableStateOf(true) }
                    val onPreferGps : (Boolean) -> Unit = { preferGPS ->
                        isPreferGps = preferGPS
                        changePrioritizedTimeSource(preferGPS)
                    }
                    var timeSource by remember { mutableStateOf("System") }
                    registerTimeSourceCallback {
                            src -> if (timeSource != src) {
                        timeSource = src
                    }
                    }
                    var nmeaDebug : Boolean by remember {
                        mutableStateOf(false)
                    }
                    val onNmeaDebug : (Boolean, String, String) -> Unit = {
                            debug, debugIp, debugPort ->
                        nmeaDebug = debug
                        nmeaDebugStateChanged(debug, debugIp, debugPort)
                    }
                    PingApp(
                        isNmeaDebugEnabled = nmeaDebug,
                        onNmeaDebugChange = onNmeaDebug,
                        isPreferGps = isPreferGps,
                        onPreferGps = onPreferGps,
                        timeSource = timeSource,
                        pingList = pingList,
                        onBind = onBind,
                        onPing = { ip, port -> ping(ip, port) { pingData ->
                            Log.d(TAG, "onPing.callback called")
                            val list = ArrayList(pingList)
                            list.add(pingData.statistics())
                            pingList = list
                        }
                        },
                        onClear = {
                            pingList = ArrayList<PingStats>()
                        },
                        verifyInputs = { ip, port -> verifyInputs.invoke(ip, port) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (udpSocket != null) {
            senderThread.quit()
            udpThread.interrupt()
            udpSocket.close()
            senderThread.join()
            udpThread.join()
        }
        super.onDestroy()
    }

    // Initialize GPS-related times
    private fun initTimeSources() {
        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("Location", "Incorrect 'uses-permission', requires 'ACCESS_COARSE_LOCATION'");
        } else {
            Log.d("Location", "Obtained 'ACCESS_COARSE_LOCATION'");
        }
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("Location", "Incorrect 'uses-permission', requires 'ACCESS_FINE_LOCATION'");
        } else {
            val mainHandler = Handler(Looper.getMainLooper())

            locationManager.registerGnssStatusCallback (gnssStatusObserver, mainHandler)
            locationManager.registerGnssNavigationMessageCallback(gnssNavigationMessageObserver, mainHandler)
            locationManager.registerGnssMeasurementsCallback(gnssMeasurementsEventObserver, mainHandler)
            locationManager.addNmeaListener(appNmeaMessageListener, mainHandler)

            Log.d("Location", "Location update requested");
            Log.d("Location", "Using GPS")
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_UPDATES_FREQUENCY_MS, 0f, appLocationListener as LocationListener)
            Log.d("Location", "Using Network")
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_UPDATES_FREQUENCY_MS, 0f, appLocationListener as LocationListener)
        }
    }

    /*private val timeSource = TimeSource.Monotonic
    private fun getCurrentTimeMark() : TimeSource.Monotonic.ValueTimeMark {
        return timeSource.markNow()
    }
    data class TimeMeasure(val receivedTimeMs: Long, val localMonotonicTM: TimeSource.Monotonic.ValueTimeMark)
    */
    fun getCurrentTimeMark() : Long {
        return SystemClock.elapsedRealtime()
    }
    data class TimeMeasure(val receivedTimeMs: Long, val localMonotonicTM: Long, val source: String)
    @Volatile
    private var lastTimeMeasure: TimeMeasure? = null
    fun setLastTimeMeasure(ltm: TimeMeasure?) {
        lastTimeMeasure = ltm
    }

    fun getCurrentTS() : Long {
        val t = lastTimeMeasure
        if (t == null) {
            // Milliseconds since epoch
            return System.currentTimeMillis()
        } else {
            val now = getCurrentTimeMark()
            val elapsed = now - t.localMonotonicTM
            val currentTS = t.receivedTimeMs + elapsed
            Log.d("TIMESTAMP", "custom ${currentTS} system ${now} delta ${currentTS-now}")
            return currentTS
        }
    }

    @Volatile
    private var timeSourceCallback: ((String) -> Unit)? = null
    private fun registerTimeSourceCallback(callback: (String) -> Unit) {
        timeSourceCallback = callback
    }
    fun updateTimeSourceText(source: String) {
        runOnUiThread {
            if (timeSourceCallback == null) {
                Log.w("TIME", "Missing timeSourceCallback")
            } else {
                timeSourceCallback?.invoke(source)
            }
        }
    }

    @Volatile
    private var PRIORITIZED_TIME_SOURCE = DEFAULT_PRIORITIZED_TIME_SOURCE
    private fun changePrioritizedTimeSource(gps: Boolean) {
        PRIORITIZED_TIME_SOURCE = if (gps)
            DEFAULT_PRIORITIZED_TIME_SOURCE
        else
            SECONDARY_PRIORITIZED_TIME_SOURCE
    }

    fun submitCandidateTime(candidate: TimeMeasure) {
        var ltm = lastTimeMeasure
        if (ltm == null) {
            // initialize
            lastTimeMeasure = candidate
            updateTimeSourceText(candidate.source)
        } else {
            // try to update
            if (ltm.receivedTimeMs < candidate.receivedTimeMs && ltm.localMonotonicTM < candidate.localMonotonicTM) {
                // received newer, use it but only if it is prioritary or enoygh time passed
                if ((ltm.localMonotonicTM + MINIMUM_LOC_UP_INTERVAL_MS < candidate.localMonotonicTM && ltm.source != PRIORITIZED_TIME_SOURCE && candidate.source != PRIORITIZED_TIME_SOURCE)
                    ||
                    (ltm.source != PRIORITIZED_TIME_SOURCE && candidate.source == PRIORITIZED_TIME_SOURCE)
                    ||
                    (ltm.receivedTimeMs < candidate.receivedTimeMs && candidate.source == PRIORITIZED_TIME_SOURCE)
                ) {
                    lastTimeMeasure = candidate
                    updateTimeSourceText(candidate.source)
                    Log.d("TIME", "Update time source with ${candidate} from ${candidate.source}")
                    Log.d("TIME", "Remote time update: ${candidate.receivedTimeMs-ltm.receivedTimeMs} local time delta ${candidate.localMonotonicTM-ltm.localMonotonicTM}")
                }
            }
        }
    }


    @Volatile
    var nmeaDebug : Boolean = false
    @Volatile
    var debugIp : String? = null
    @Volatile
    var debugPort : String? = null
    private fun nmeaDebugStateChanged(debug: Boolean, debugIp : String?, debugPort : String?) {
        nmeaDebug = debug
        if (debug) {
            this.debugIp = debugIp
            this.debugPort = debugPort
        }
    }
    data class DebugMsg(val ip : InetAddress,
                        val port : Int,
                        val message : String)
    fun sendDebugMsg(message: String) {
        val debugIp = this.debugIp
        val debugPort = this.debugPort
        if (nmeaDebug && debugIp != null && debugPort != null) {
            val debugMsg = DebugMsg(
                ip = InetAddress.getByName(debugIp),
                port = debugPort.toInt(),
                message = message)
            var msg = senderHanlder.obtainMessage(DEBUG_MSG, debugMsg)
            senderHanlder.sendMessage(msg)
        }
    }
    fun debugNmea(nmeaMessage: String) {
        sendDebugMsg("NMEA DEBUG: ${nmeaMessage}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingApp(
    isNmeaDebugEnabled: Boolean,
    onNmeaDebugChange: (Boolean, String, String) -> Unit,
    isPreferGps: Boolean,
    onPreferGps: (Boolean) -> Unit,
    timeSource: String,
    pingList: List<MainActivity.PingStats>,
    verifyInputs : (String, String) -> Boolean,
    onBind: (String) -> Boolean,
    onPing: (String, String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bound by remember { mutableStateOf(false) }
    var localPort by remember { mutableStateOf("") }
    var remoteIp by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("") }

    val debugCallback : (Boolean) -> Unit = { debug ->
        if (!isNmeaDebugEnabled && debug) {
            if (!verifyInputs(remoteIp, remotePort)) {
                Log.d("DEBUG", "Cannot enable debug with invalid endpoint '${remoteIp}':'${remotePort}'")
            } else {
                Log.d("DEBUG", "Send NMEA debug to '${remoteIp}':'${remotePort}'")
                onNmeaDebugChange(debug, remoteIp, remotePort)
            }
        } else if (!debug) {
            onNmeaDebugChange(debug, remoteIp, remotePort)
        }
    }

    val isInputValid = verifyInputs.invoke(remoteIp, remotePort)
    val pingCbk = { onPing.invoke(remoteIp, remotePort) }
    Column(
        modifier = Modifier.padding(all = 10.dp)
    ) {
        // Should use GPS as time source
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Prefer GPS over Network"
            )
            Switch(
                checked = isPreferGps,
                onCheckedChange = onPreferGps
            )
        }
        // Last used time source
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Time source:",
                modifier = Modifier.weight(1.0f)
            )
            Text(
                text = timeSource,
                modifier = Modifier.weight(1.0f),
                textAlign = TextAlign.Center
            )
        }
        // Debug enabled?
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Send NMEA debug info?"
            )
            Switch(
                checked = isNmeaDebugEnabled,
                onCheckedChange = debugCallback
            )
        }
        // Main form
        InitForm(
            localPort = localPort,
            onLocalPortInput = { localPort = it },
            bound = bound,
            onBind = { bound = onBind.invoke(localPort) }
        )
        Spacer(
            modifier = Modifier.height(15.dp)
        )
        PingForm(
            remotePort = remotePort,
            onRemotePortInput = { remotePort = it },
            remoteIp = remoteIp,
            onIpInput = { remoteIp = it.trim() },
            bound = bound,
            isInputValid = isInputValid,
            onPing = pingCbk,
            onClear = onClear
        )
        LazyColumn(modifier = modifier) {
            items(
                items = pingList,
                key = { ps -> ps.uuid }
            ) { ps ->
                PingStatsBox(ps = ps)
                /*Text(
                    text = "${ps}"
                )*/
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitForm(
    localPort : String,
    onLocalPortInput : (String) -> Unit,
    bound : Boolean,
    onBind : () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Local port:"
        )
        Spacer(
            modifier = Modifier.width(5.dp)
        )
        TextField(
            singleLine = true,
            value = localPort,
            onValueChange = onLocalPortInput,
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.weight(0.7f),
            enabled = !bound
        )
        Spacer(
            modifier = Modifier.weight(0.3f)
        )
        Button(
            onClick = onBind,
            modifier = Modifier.weight(
                1.0f
            ),
            enabled = !bound
        ) {
            Text(
                text = if (!bound) "Bind"
                else "Bound"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingForm(
    remotePort : String,
    onRemotePortInput : (String) -> Unit,
    remoteIp : String,
    onIpInput : (String) -> Unit,
    bound : Boolean,
    isInputValid : Boolean,
    onPing : () -> Unit,
    onClear : () -> Unit
) {
    if (!bound) {
        Text(
            text = "Wait for local binding...",
            modifier = Modifier
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Remote IP",
                modifier = Modifier.weight(1.0f)
            )
            TextField(
                singleLine = true,
                value = remoteIp,
                onValueChange = onIpInput,
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.weight(1.0f)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Remote port",
                modifier = Modifier.weight(1.0f)
            )
            TextField(
                singleLine = true,
                value = remotePort,
                onValueChange = onRemotePortInput,
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.weight(1.0f)
            )
        }
        Spacer(
            modifier = Modifier.height(20.dp)
        )
        Button(
            enabled = isInputValid,
            onClick = if (isInputValid) onPing else { {} },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (isInputValid) "PING!" else "Invalid input",
                fontSize = 30.sp
            )
        }
        Button(
            onClick = onClear,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Clear",
                fontSize = 20.sp
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
fun PingFormPreview() {
    UniPingAppTheme {
        PingApp(
            timeSource = "System",
            pingList = Collections.singletonList(
                MainActivity.PingStats(
                    ip = InetAddress.getByName("127.0.0.1"),
                    port = 8080,
                    uuid = UUID.randomUUID() ,
                    reps = 10)
            ),
            onPing = { a,b -> Unit },
            onBind = { a -> false },
            verifyInputs = {a,b-> false },
            onClear = {},
            isPreferGps = false,
            onPreferGps = {},
            isNmeaDebugEnabled = false,
            onNmeaDebugChange = { a,b,c -> Unit }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditNumberField(
    @StringRes label: Int,
    @DrawableRes leadingIcon: Int,
    keyboardOptions: KeyboardOptions,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        label = {
            Text(stringResource(id = label))
        },
        leadingIcon = {
            Icon(painter = painterResource(id = leadingIcon), null)
        },
        singleLine = true,
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = keyboardOptions,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditIpField(
    @StringRes label: Int,
    @DrawableRes leadingIcon: Int,
    keyboardOptions: KeyboardOptions,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        label = {
            Text(stringResource(id = label))
        },
        leadingIcon = {
            Icon(painter = painterResource(id = leadingIcon), null)
        },
        singleLine = true,
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = keyboardOptions,
        modifier = modifier
    )
}

@Composable
@Preview
fun PingStatsBoxPreview() {
    UniPingAppTheme {
        PingStatsBox(
            ps = MainActivity.PingStats(
                uuid = UUID.randomUUID(),
                ip = InetAddress.getByName("127.0.0.1"),
                port = 8080,
                reps = 100, lost = 15,
                max1 = 10, max2 = 20, rttMax = 30,
                min1 = 4, min2 = 8, rttMin = 9,
                mean1 = 7f, mean2 = 18f, rttMean = 13f,
                var1 = 88.2145f, var2 = 5.1236f, rttVar = 887.7f
            )
        )
    }
}

@Composable
fun PingStatsBox(
    ps: MainActivity.PingStats,
    modifier: Modifier = Modifier
) {
    Column {
        // UUID
        Row (
            modifier = Modifier.padding(top = 5.dp)
        ) {
            Text(
                text = ps.uuid.toString(),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(alignment = Alignment.CenterVertically)
            )
        }
        // Remote
        Row {
            Text(text = "Remote")
            Text(text = "${ps.ip}:${ps.port}")
        }
        Row {
            Text(text = "Repetitions")
            Text(text = "${ps.reps}")
        }
        Spacer(modifier = Modifier.height(2.dp))
        // BODY - table
        // request - response - rtt
        Row {
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "request",
                modifier = Modifier.weight(1f))
            Text(text = "response",
                modifier = Modifier.weight(1f))
            Text(text = "rtt",
                modifier = Modifier.weight(1f))
        }
        Row {
            Text(text = "min",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.min1}",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.min2}",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.rttMin}",
                modifier = Modifier.weight(1f))
        }
        Row {
            Text(text = "max",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.max1}",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.max2}",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.rttMax}",
                modifier = Modifier.weight(1f))
        }
        Row {
            Text(text = "mean",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.mean1}",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.mean2}",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.rttMean}",
                modifier = Modifier.weight(1f))
        }
        Row {
            Text(text = "var",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.var1}",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.var2}",
                modifier = Modifier.weight(1f))
            Text(text = "${ps.rttVar}",
                modifier = Modifier.weight(1f))
        }
    }
}
