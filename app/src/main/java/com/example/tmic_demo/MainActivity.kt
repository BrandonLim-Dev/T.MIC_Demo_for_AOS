package com.example.tmic_demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecord.getMinBufferSize
import android.media.MediaRecorder
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tmic_demo.databinding.ActivityMainBinding
import com.example.tmic_demo.databinding.TextItemBinding
import org.json.JSONObject
import org.json.JSONTokener
import java.io.*
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.*
import java.util.regex.Pattern
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // region <!-- Global Variables --!>
    // DEF View Binding
    private lateinit var binding : ActivityMainBinding

    companion object VARIABLES{
        // DEF for Voice Recorder
        const val PERM_RECORD_AUDIO_CODE:Int = 100
        const val AUDIO_SOURCE:Int = MediaRecorder.AudioSource.MIC
        const val SAMPLE_RATE:Int = 16000
        const val CHANNEL_COUNT:Int = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT:Int = AudioFormat.ENCODING_PCM_16BIT

        // DEF for Data Communication
        const val HEADER_LENGTH:Int = 8
        const val VERSION_LENGTH:Int = 1
        const val MSGCODE_LENGTH:Int = 1
        const val SEQUENCE_LENGTH:Int = 2
        const val PAYLOAD_LENGTH:Int = 4

        const val CODE_SESSIONCONTROL:String = "01"
        const val CODE_STREAMTRANSCRIBE:String = "02"
    }

    // DEF Permission
    private val PERMISSIONS_REQUEST_CODE = 1
    private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.RECORD_AUDIO)

    // DEF Network
    private var isConnected:Boolean = false
    private var isConnecting:Boolean = false
    var ipAddress:String = ""
    var portNumber:Int = 0
    var protocol:String = ""
    var seq:Int = -1

    var tcpSocket:Socket? = null
    lateinit var tcpWStream:OutputStream        // 데이터 송신
    lateinit var tcpRStream:InputStream        // 데이터 수신

    // DEF Voice Recorder
    private var isRecording = false
    private var isMute = true

    private lateinit var mAudioRecord:AudioRecord
    private var buffSize:Int = getMinBufferSize(SAMPLE_RATE, CHANNEL_COUNT, AUDIO_FORMAT)
    lateinit var recordingThread:Thread

    // DEF RecyclerView
    private val rvItems = mutableListOf<SttTextItem>()

    // endregion <!-- Global Variables --!>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FUNC View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        // FUNC Default Value
        runOnUiThread { binding.editTextIPAddress.setText(R.string.def_ip) }
        runOnUiThread { binding.editTextPortNumber.setText(R.string.def_port) }

        // FUNC Spinner
        val data = listOf("TCP/IP", "UDP")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, data)
        binding.spinner.adapter = adapter
        binding.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {
                TODO("Not yet implemented")
            }
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                when (p2)
                {
                    // FUNC TCP/IP
                    0 -> {
                        Log.i("spinner", "TCP/IP")
                        protocol = "TCP/IP"
                    }
                    // FUNC UDP
                    1 -> {
                        Log.i("spinner", "UDP")
                        protocol = "UDP"
                    }
                }
            }
        }

        // FUNC: Check Permission
        checkPermission(
            cancel = {
                showPermissionInfoDialog()
            },
            ok = {
                // NOTE : 마이크 권한이 있을 경우
                Log.i("MIC", "[RECORD_AUDIO] Permission is PASS")
                mAudioRecord = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_COUNT, AUDIO_FORMAT, buffSize)
            }
        )

        // FUNC: RecyclerView Setting
        binding.recyclerview.layoutManager = LinearLayoutManager(this)
        binding.recyclerview.adapter = MyAdapter(rvItems)
       // binding.recyclerview.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        binding.btnConn.setOnClickListener {

            if(isConnected) // NOTE: 현재 연결됨 상태
            {
                Log.d("CONN", "연결중 -> 연결요청")
                isConnected = false
                isConnecting = false
                isMute = true
                runOnUiThread { binding.btnConn.text = "연결요청" }
                runOnUiThread { binding.btnMute.text = "마이크 꺼짐" }
                runOnUiThread { binding.btnConn.setBackgroundColor(ContextCompat.getColor(this, R.color.red)) }
                runOnUiThread { binding.btnMute.setBackgroundColor(ContextCompat.getColor(this, R.color.red)) }

                // FUNC: Stop Recording
                if(isRecording)
                {
                    mAudioRecord.stop()
                    //mAudioRecord.release()
                    isRecording = false
                }

                // FUNC: reset UI
                //  Network Setting
                binding.editTextIPAddress.isEnabled = true
                binding.editTextPortNumber.isEnabled = true
                binding.spinner.isEnabled = true
                //  RecyclerView
                rvItems.clear()
                binding.recyclerview.layoutManager = LinearLayoutManager(parent)
                binding.recyclerview.adapter = MyAdapter(rvItems)

                // FUNC: TCP/IP Disconnect()
                if(!tcpSocket?.isClosed!!)
                {
                    TcpDisconnect().start()
                }
            }
            else {
                if (isConnecting) // NOTE: 현재 연결요청중 상태
                {
                    Log.d("CONN", "연결시도 -> 연결요청")
                    isConnecting = false
                    isConnected = false
                    runOnUiThread { binding.btnConn.text = "연결요청" }
                    runOnUiThread { binding.btnMute.text = "마이크 꺼짐" }
                    runOnUiThread { binding.btnConn.setBackgroundColor(ContextCompat.getColor(this, R.color.red)) }
                    runOnUiThread { binding.btnMute.setBackgroundColor(ContextCompat.getColor(this, R.color.red)) }

                    // NOTE: 연결 시도중 중단했을 경우 TCP/IP Receive 중단
                    TcpReceive().interrupt()

                    // FUNC: reset UI
                    //  Network Setting
                    binding.editTextIPAddress.isEnabled = true
                    binding.editTextPortNumber.isEnabled = true
                    binding.spinner.isEnabled = true
                    //  RecyclerView
                    rvItems.clear()
                    binding.recyclerview.layoutManager = LinearLayoutManager(parent)
                    binding.recyclerview.adapter = MyAdapter(rvItems)
                }
                else { // NOTE: 현재 연결요청 상태

                    // FUNC : Check Permission
                    val Permission = Manifest.permission.RECORD_AUDIO
                    if (isPermissionGranted(this, Permission)) {
                        Log.d("PERM", "권한이 허용되어 있음.")
                        mAudioRecord = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_COUNT, AUDIO_FORMAT, buffSize)
                    } else {
                        Log.d("PERM", "권한이 허용되어 있지 않음.")
                        Toast.makeText(this, "마이크 권한이 거부되어 있습니다. 권한을 허용해주세요.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // FUNC:
                    //   TCP/IP Connect()
                    //   Send SessionControl
                    if(binding.editTextIPAddress.text.toString().trim().isEmpty()){
                        onlyTxtDialog("IP 주소를 입력해 주세요")
                    }
                    else {
                        // FUNC: IP 주소 유효성 검사
                        ipAddress = binding.editTextIPAddress.text.toString().trim()
                        if(!Pattern.matches("([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})", ipAddress)){
                            onlyTxtDialog("올바른 IP주소 형식이 아닙니다.")
                        }
                        else {
                            // FUNC: PORT 번호 확인
                            if(binding.editTextPortNumber.text.toString().trim().isEmpty()){
                                onlyTxtDialog("Port 번호를 입력해 주세요.")
                            }
                            else {
                                portNumber = binding.editTextPortNumber.text.toString().trim().toInt()
                                if (portNumber < 0 || portNumber > 65535)
                                {
                                    onlyTxtDialog("잘못된 Port 범위 입니다. (0~65535)")
                                }
                                else {
                                    // FUNC: Protocol Spinner 확인
                                    if(binding.spinner.selectedItem.toString() == "UDP")
                                    {
                                        onlyTxtDialog("UDP는 아직 지원하지 않습니다.")
                                    }
                                    else { // NOTE: TCP/IP
                                        isConnecting = true
                                        isConnected = false
                                        runOnUiThread { binding.btnConn.text = "연결시도" }
                                        runOnUiThread { binding.btnConn.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.amber)) }
                                        //  Network Setting
                                        binding.editTextIPAddress.isEnabled = false
                                        binding.editTextPortNumber.isEnabled = false
                                        binding.spinner.isEnabled = false

                                        // FUNC: Try TCP/IP Connect
                                        TcpConnect().start()
                                        // NOTE: TCP/IP 연동이 연결되었는지 확인할 수 있는 방법이 없으므로 3초뒤 TCP Socket 상태 확인
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            if(tcpSocket == null)
                                            {
                                                Log.e("TCP", "tcpSocket is Null")
                                            }
                                            else {
                                                // NOTE: TCP/IP 연결이 확인될 경우 TcpReceive() Thread 동작
                                                if(tcpSocket?.isConnected!!)
                                                {
                                                    if(isConnecting)
                                                        TcpReceive().start()
                                                }
                                            }
                                        }, 3000)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        binding.btnMute.setOnClickListener {
            if(isConnected)
            {
                if(isMute)
                {
                    isMute = false
                    runOnUiThread { binding.btnMute.text = "마이크 켜짐" }
                    runOnUiThread { binding.btnMute.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.teal)) }
                    Log.d("MUTE", "UNMUTE")
                }
                else {
                    isMute = true
                    runOnUiThread { binding.btnMute.text = "마이크 꺼짐" }
                    runOnUiThread { binding.btnMute.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.red)) }
                    Log.d("MUTE", "MUTE")
                }
            }
            else {
                Toast.makeText(this, "네트워크가 연결되지 않았습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // region <!-- Permission !-->
    private fun checkPermission(cancel:()->Unit, ok:()->Unit)
    {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        {
            Log.i("Permission", "권한 없음")
            // NOTE: 현재 RECORD_AUDIO 권한이 없는 경우
            if(ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO))
            {
                // NOTE: 사용자가 권한을 거부한 적이 있는 경우
                cancel()
            }
            else {
                // NOTE: 최초 권한 요청이거나 권한을 허용한 경우
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        else{
            Log.i("Permission", "권한 있음")
        }
        ok()
    }

    private fun negativeFunc()
    {
        Toast.makeText(this, "마이크 권한이 거부되어 있습니다. 권한을 허용해주세요.", Toast.LENGTH_SHORT).show()
    }

    private fun showPermissionInfoDialog() {
        AlertDialog.Builder(this).apply{
            setTitle("권한 요청 이유")
            setMessage("음성 전송을 위해 마이크 권한이 필요합니다.")
            setPositiveButton("권한 요청") {_, _->
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            setNegativeButton("거부") {_, _->
                negativeFunc()
            }
        }.show()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission())
        {
                isGranted ->
            if (isGranted){
                // FUNC
                //      start APP
            }
            else {
                //Toast.makeText(this, "권한을 받아오지 못해 앱을 종료합니다.", Toast.LENGTH_SHORT).show()
            }

        }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        val granted = PackageManager.PERMISSION_GRANTED
        return context.checkSelfPermission(permission) == granted
    }
    // endregion <!-- Permission !-->

    // region <!-- Network 처리 영역 !-->
    // FUNC
    //      TCP/IP Connect
    inner class TcpConnect : Thread()
    {
        override fun run() {
            Log.i("TCP", "tcpConnect() - start -")
            try {
                super.run()
                tcpSocket = Socket(ipAddress, portNumber)
                tcpWStream = DataOutputStream(tcpSocket?.getOutputStream())
                tcpRStream = DataInputStream(tcpSocket?.getInputStream())

                if (tcpSocket?.isConnected!!) {
                    // FUNC : send SessionControl
                    try {
                        // FUNC : make Body
                        val body: String = reqSessionControl("WHISPER")
                        if (body.isEmpty())
                        {
                            onlyTxtDialog("연결 요청에 실패했습니다. (메시지 생성 실패)")
                            isConnected = false
                            isConnecting = false
                            runOnUiThread { binding.btnConn.text = "연결요청" }
                            runOnUiThread { binding.btnMute.text = "마이크 꺼짐" }
                            runOnUiThread { binding.btnConn.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.red)) }
                            runOnUiThread { binding.btnMute.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.red)) }

                            // FUNC: Stop Recording
                            if(isRecording)
                            {
                                mAudioRecord.stop()
                                //mAudioRecord.release()
                                isRecording = false
                            }

                            // FUNC: reset UI
                            //  Network Setting
                            binding.editTextIPAddress.isEnabled = true
                            binding.editTextPortNumber.isEnabled = true
                            binding.spinner.isEnabled = true
                            //  RecyclerView
                            rvItems.clear()
                            binding.recyclerview.layoutManager = LinearLayoutManager(parent)
                            binding.recyclerview.adapter = MyAdapter(rvItems)

                            // FUNC: TCP/IP Disconnect()
                            if(!tcpSocket?.isClosed!!)
                            {
                                TcpDisconnect().start()
                            }
                        }
                        else {
                            // FUNC : make TCP/IP Header
                            val version = byteArrayOf(0x00)
                            val msgcode = byteArrayOf(0x01)
                            seq += 1
                            val header: ByteArray = makeTcpHeader(version, msgcode, seq, body.length)
                            // FUNC : send Header
                            Log.i("SEND" ,"Header : ${header.toHexString()}")
                            tcpWStream.write(header)
                            // FUNC : send Body
                            Log.i("SEND" ,"Body : $body, Len : ${body.length}")
                            tcpWStream.write(body.toByteArray())
                        }
                    }
                    catch (e:SocketException) // NOTE: TCP Socket Exception
                    {
                        e.printStackTrace()
                    }
                    catch (e:IOException) // NOTE: TCP STREAM WRITE IOException
                    {
                        e.printStackTrace()
                    }
                    catch (e:Exception) // NOTE: Normal Exception
                    {
                        e.printStackTrace()
                    }
                }
                else {

                }
            } catch (se: SocketException) {
                se.printStackTrace()
                TcpDisconnect().start()
                runOnUiThread {
                    binding.editTextIPAddress.isEnabled = true
                    binding.editTextPortNumber.isEnabled = true
                    binding.spinner.isEnabled = true

                    rvItems.clear()
                    binding.recyclerview.layoutManager = LinearLayoutManager(parent)
                    binding.recyclerview.adapter = MyAdapter(rvItems)
                }
            }
        }
    }

    // FUNC
    //      TCP/IP Disconnect
    inner class TcpDisconnect : Thread()
    {
        override fun run() = try {
            super.run()
            Log.i("TCP", "tcpDisconnect() - start -")
            TcpConnect().interrupt()
            TcpReceive().interrupt()
            tcpRStream.close()
            tcpWStream.close()
            tcpSocket?.close()!!
        }
        catch (e:SocketException) // NOTE: TCP Socket Exception
        {
            e.printStackTrace()
        }
        catch (e:IOException) // NOTE: TCP STREAM READ IOException
        {
            e.printStackTrace()
        }
        catch (e:Exception) // NOTE: Normal Exception
        {
            e.printStackTrace()
        }
    }

    // FUNC
    //      TCP/IP Client Receive Thread
    //      1. 8byte Header 수신 후 [MsgCode] 확인 --> 01 : SessionControl, 02 : StreamTranscribe
    //      2. [MsgCode]로 메시지 타입 구분
    //      3. [PayloadLength]로 Body 길이 획득
    //      4. Body 데이터 수신
    inner class TcpReceive : Thread()
    {
        override fun run() {
            super.run()
            Log.i("TCP", "tcpReceive() - start -")

            try {
                while (tcpSocket?.isConnected!!)
                {
                    if (tcpRStream.available() > 0) // NOTE: TCP/IP Buffer에 Data가 있을 경우
                    {
                        val header = ByteArray(HEADER_LENGTH)
                        tcpRStream.read(header, 0, HEADER_LENGTH)
                        //val data = rByte.toString(Charsets.UTF_8)
                        Log.i("TCP-R", "data : ${header.toHexString()}")

                        // FUNC :: get Header (8 byte)
                        //  version (1byte)
                        //  MsgCode (1byte)
                        //  Sequence (2byte)
                        //  PayloadLength (4byte)
                        // DEF 1. version (1byte)
                        val Version:ByteArray = header.copyOfRange(0, 1)
                        Log.d("TCP-R", "version : ${Version.toHexString()}")
                        // DEF 2. MsgCode (1byte)
                        val MsgCode:ByteArray = header.copyOfRange(1,2)
                        Log.d("TCP-R", "MsgCode : ${MsgCode.toHexString()}")
                        // DEF 3. Sequence (2byte)
                        val Sequence:ByteArray = header.copyOfRange(2,4)
                        Log.d("TCP-R", "Sequence : ${Sequence.toHexString()}")
                        // DEF 4. PayloadLength (4byte)
                        val PayloadLength:ByteArray = header.copyOfRange(4,8)
                        val intPayloadLength:Int = ByteBuffer.wrap(PayloadLength).int
                        Log.d("TCP-R", "PayloadLength : ${PayloadLength.toHexString()} -> $intPayloadLength")

                        // FUNC :: get Body
                        val tmpBody = ByteArray(intPayloadLength)
                        tcpRStream.read(tmpBody, 0, intPayloadLength)
                        val body = tmpBody.toString(Charsets.UTF_8)
                        Log.d("TCP-R", "Body : $body")

                        if (MsgCode.toHexString() == CODE_SESSIONCONTROL)
                        {
                            Log.i("TCP-R", "SessionControl")
                            val result: String = resSessionControl(body)
                            if (result == "0")
                            {
                                Log.i("TCP-R", "STT Model 연결 완료!!")
                                isConnected = true
                                isConnecting = false
                                // FUNC 설정값 변경 불가
                                binding.editTextIPAddress.isEnabled = false
                                binding.editTextPortNumber.isEnabled = false
                                binding.spinner.isEnabled = false
                                runOnUiThread { binding.btnConn.text = "연결됨" }
                                runOnUiThread { binding.btnConn.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.teal)) }

                                // FUNC AudioRecord 활성
                                if(mAudioRecord.state == AudioRecord.STATE_UNINITIALIZED)
                                {
                                    Log.e("AUDIO", "Failed to initialize AudioRecord")
                                }
                                else {
                                    //val deviceList:List<AudioDeviceInfo> = getMicrophoneDeviceInfo()
                                    //for (device in deviceList)
                                    //{
                                    //    Log.i("MIC", "device : ${device.productName}")
                                    //}

                                    mAudioRecord.startRecording()
                                    isRecording = true

                                    // FUNC AudioRecord Thread
                                    recordingThread = thread {

                                        val audioData = ByteArray(buffSize)
                                        Log.d("AUDIO", "녹음 시작")
                                        while (isRecording)
                                        {
                                            var readByte: Int? = mAudioRecord.read(audioData, 0, buffSize)
                                            if(readByte == buffSize)
                                            {
                                                seq += 1
                                                //Log.d("AUDIO", "seq : $seq, read bytes : $readByte, audioData.size : ${audioData.size}")

                                                // FUNC : make TCP/IP Header
                                                val version = byteArrayOf(0x00)
                                                val msgcode = byteArrayOf(0x02)
                                                val header: ByteArray = makeTcpHeader(version, msgcode, seq, audioData.size)
                                                // FUNC : send Header
                                                //Log.i("SEND", "Header : ${header.toHexString()}")
                                                tcpWStream.write(header)
                                                // FUNC : send Body
                                                if(isMute)
                                                    audioData.fill(0x00)
                                                 //Log.i("SEND", "Body : ${audioData.toHexString()}")
                                                tcpWStream.write(audioData)
                                            }
                                        }
                                    }
                                    if(recordingThread.state == State.NEW)
                                    {
                                        recordingThread.start()
                                    }
                                }
                            }
                            else { // DEF SessionControl Error
                                Log.e("TCP-R", "ERROR : $result")
                            }
                        }
                        else if (MsgCode.toHexString() == CODE_STREAMTRANSCRIBE)
                        {
                            Log.i("TCP-R", "StreamTranscribe")
                            val (start, end, txt) = resStreamTranscribe(body)
                            Log.i("TCP-R", "start : $start, end : $end, txt : $txt")
                            // NOTE: convert start, end to TIME format
                            val (tStart, tEnd) = convertMiltoTime(start, end)
                            // NOTE: end
                            runOnUiThread { addRVItems(rvItems, tStart, tEnd, txt) }
                        }
                        else {
                            Log.e("TCP-R", "MsgCode is Invalid (${MsgCode.toHexString()})")
                        }
                    }
                }
            }
            catch (e:SocketException) // NOTE: TCP Socket Exception
            {
                e.printStackTrace()
            }
            catch (e:IOException) // NOTE: TCP STREAM READ IOException
            {
                e.printStackTrace()
            }
            catch (e:Exception) // NOTE: Normal Exception
            {
                e.printStackTrace()
            }
        }
    }

    private fun intToByteArray(value:Int):ByteArray{
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun shortToByteArray(value:Short):ByteArray{
        val byteArray = ByteArray(SEQUENCE_LENGTH)
        byteArray[0] = (value.toInt() shr 8 and 0xFF).toByte()
        byteArray[1] = (value.toInt() and 0xFF).toByte()
        return byteArray
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun ByteArray.toHexString() = asUByteArray().joinToString(" ") { it.toString(16).padStart(2, '0')}

    private fun makeTcpHeader(
        ver: ByteArray,
        msgCode: ByteArray,
        seq: Int,
        length: Int
    ): ByteArray {
        val Seq: Short = seq.toShort()
        val seqRet = shortToByteArray(Seq)
        val lengthRet = intToByteArray(length)

        return ver + msgCode + seqRet + lengthRet
    }
    // endregion <!-- Network 처리 영역 !-->

    // region <!-- AudioRecord 처리 영영 !-->
    fun getMicrophoneDeviceInfo(): List<AudioDeviceInfo> {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val microphoneDevices = devices.filter { device ->
            device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                    device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        return microphoneDevices
    }
    // endregion <!-- AudioRecord 처리 영영 !-->

    // region <!-- JSON 처리 --!>
    private fun reqSessionControl(modelName : String): String {
        // DEF : make JSON String
        //  [SessionControl]
        //  {
        //     "MsgType" : "ConnectModel",
        //     "MsgData" :
        //     {
        //         "ModelName" : "WHISPER"
        //     }
        //  }
        val msgData = JSONObject()
        msgData.put("ModelName", modelName)

        val jsonMsg = JSONObject()
        jsonMsg.put("MsgType", "ConnectModel")
        jsonMsg.put("MsgData", msgData)

        return jsonMsg.toString()
    }

    private fun resSessionControl(message:String): String {
        val jsonObject = JSONTokener(message).nextValue() as JSONObject

        val result:String = jsonObject.getString("result")
        val reason:String = jsonObject.getString("reason")

        Log.i("JSON", "result : $result, reason : $reason")
        return result
    }

    //private fun reqStreamTranscribe(){
//
    //}

    private fun resStreamTranscribe(message:String): Triple<String, String, String>{

        val jsonObject = JSONTokener(message).nextValue() as JSONObject

        val start:String = jsonObject.getString("start")
        val end:String = jsonObject.getString("end")
        val txt:String = jsonObject.getString("txt")

        return Triple(first = start, second = end, third = txt)
    }

    private fun convertMiltoTime(startMil:String, endMil:String): Pair<String, String>{

        val sMilliseconds:Long = startMil.toLong()
        val eMilliseconds:Long = endMil.toLong()

        // FUNC: make Start
        var seconds = sMilliseconds / 1000
        var minutes = seconds / 60
        var hours = minutes / 60
        var remainingMinutes = minutes % 60
        var remainingSeconds = seconds % 60
        var remainingMilliseconds = sMilliseconds % 1000
        val start:String = String.format("%02d:%02d:%02d.%03d", hours, remainingMinutes, remainingSeconds, remainingMilliseconds)

        // FUNC: make End
        seconds = eMilliseconds / 1000
        minutes = seconds / 60
        hours = minutes / 60
        remainingMinutes = minutes % 60
        remainingSeconds = seconds % 60
        remainingMilliseconds = eMilliseconds % 1000
        val end:String = String.format("%02d:%02d:%02d.%03d", hours, remainingMinutes, remainingSeconds, remainingMilliseconds)

        return Pair(first = start, second = end)
    }
    // endregion <!-- JSON 처리 --!>

    // region <!-- RecyclerView 영역 --!>
    class MyViewHolder(val binding:TextItemBinding):RecyclerView.ViewHolder(binding.root)

    class MyAdapter(private val rvItems:MutableList<SttTextItem>): RecyclerView.Adapter<RecyclerView.ViewHolder>(){
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return MyViewHolder(TextItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            Log.d("RV", "onBindViewHolder : $position")
            val binding = (holder as MyViewHolder).binding
            binding.startTime.text = rvItems[position].startTime
            binding.endTime.text = rvItems[position].endTime
            binding.sttText.text = rvItems[position].sttText
        }

        override fun getItemCount(): Int = rvItems.size

    }
    // endregion <!-- RecyclerView 영역 --!>

    // region <!-- Inner Class 영역 --!>
    private fun onlyTxtDialog(content:String)
    {
        AlertDialog.Builder(this).apply {
            setMessage(content)
            setNeutralButton("확인", null)
        }.show()
    }

    private fun addRVItems(rvItems: MutableList<SttTextItem>, start:String, end:String, txt:String)
    {
        rvItems.add(SttTextItem(start, end, txt))
        binding.recyclerview.layoutManager = LinearLayoutManager(this)
        binding.recyclerview.adapter = MyAdapter(rvItems)
        binding.recyclerview.scrollToPosition(rvItems.size - 1)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        val imm: InputMethodManager =
            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        Log.i("INFO","onPause()")
    }

    override fun onResume() {
        super.onResume()
        Log.i("INFO","onResume()")
    }

    override fun onStop() {
        super.onStop()
        Log.i("INFO","onStop()")
    }

    override fun onStart() {
        super.onStart()
        Log.i("INFO","onStart()")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("INFO","onDestroy()")
    }

    override fun onRestart() {
        super.onRestart()
        Log.i("INFO","onRestart()")
    }
}