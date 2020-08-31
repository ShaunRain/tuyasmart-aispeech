package com.tuya.smart.iotgateway.speech

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build.VERSION
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import com.aispeech.dui.dds.DDS
import com.aispeech.dui.dds.DDSAuthListener
import com.aispeech.dui.dds.DDSConfig
import com.aispeech.dui.dds.DDSInitListener
import com.aispeech.dui.dds.agent.ASREngine
import com.aispeech.dui.dds.agent.MessageObserver
import com.aispeech.dui.dds.agent.tts.TTSEngine
import com.aispeech.dui.dds.agent.wakeup.word.WakeupWord
import com.aispeech.dui.dds.exceptions.DDSNotInitCompleteException
import com.aispeech.dui.dsk.duiwidget.CommandObserver
import com.alibaba.fastjson.JSON
import com.tuya.smart.iotgateway.gateway.GatewayError.ERROR_NETWORK_ERROR
import com.tuya.smart.iotgateway.gateway.TuyaIotGateway
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.json.JSONObject
import pub.devrel.easypermissions.EasyPermissions
import java.io.File


class SpeechHelper(val context: Context, val configPth: String, val callback: OnSpeechCallback, val timeout: Int, val deviceId: String) {

    companion object {
        private const val TAG: String = "SpeechHelper"

        public var enableDebug = true

        private fun LOGD(msg: String) {
            if (enableDebug) {
                Log.d(TAG, msg)
            }
        }

        private fun LOGE(msg: String) {
            if (enableDebug) {
                Log.e(TAG, msg)
            }
        }

        private const val ERROR_OK = 0
        private const val ERROR_SPEECH_DIALOG_ERROR = -8
    }

    private var mSpeechThread: HandlerThread? = null
    private var mSpeechHandler: Handler? = null

    var greeting: String = ""
    private var mIsListening: Boolean = false

    private val mSubscribeKeys = arrayOf("sys.dialog.state", "context.output.text", "context.input.text", "context.widget.content", "context.widget.list", "context.widget.web", "context.widget.media", "sys.dialog.start", "sys.dialog.error", "sys.dialog.end")

    var errCode: Int = ERROR_OK

    private val messageObserver: MessageObserver = MessageObserver { message, data ->
        run {
            Log.w(TAG, "message: $message")
            when (message) {
                "sys.dialog.end" -> {
                    callback.onResponse(errCode == ERROR_OK, arrayListOf())
                    callback.onSpeechEnd(errCode)
                }
                "sys.dialog.error" -> {
                    speak("我听不懂你在说什么")
                    errCode = ERROR_SPEECH_DIALOG_ERROR
                }
                "sys.dialog.start" -> {
                    errCode = ERROR_OK
                    callback.onSpeechBeginning(errCode)
                }
            }
        }
    }

    private val commandObserver: CommandObserver = CommandObserver { command, data ->
        run {
            Log.w(TAG, "command: $command")
            callback.onCommand(command, data)
            DDS.getInstance().agent.stopDialog()
        }
    }

    private var mInitListener: DDSInitListener = object : DDSInitListener {
        override fun onInitComplete(isFull: Boolean) {
            LOGD("onInitComplete $isFull")
            if (isFull) {
                try {
                    DDS.getInstance().agent.ttsEngine.setMode(TTSEngine.LOCAL)
                    DDS.getInstance().agent.ttsEngine.volume = 100
                    DDS.getInstance().doAuth()
                } catch (e: DDSNotInitCompleteException) {
                    LOGE("onInitComplete $e")

                    callback.onInitError(e.toString())
                }
            }
        }

        override fun onError(what: Int, msg: String?) {
            LOGE("Init onError: $what, error: $msg")

            callback.onInitError("Init onError: $what, error: $msg")
        }
    }

    private var mAuthListener: DDSAuthListener = object : DDSAuthListener {
        override fun onAuthFailed(errId: String?, error: String?) {

            LOGE("onAuthFailed: $errId, error:$error")

            callback.onInitError("onAuthFailed: $errId, error:$error")

//            try {
//                DDS.getInstance().doAuth()
//            } catch (e: DDSNotInitCompleteException) {
//                LOGE("onAuthFailed $e")
//
//                callback.onInitError("onAuthFailed $e")
//            }
        }

        override fun onAuthSuccess() {
            LOGD("onAuthSuccess")

            DDS.getInstance().agent.subscribe(mSubscribeKeys, messageObserver)
            if (callback.getCommands().isNotEmpty()) {
                DDS.getInstance().agent.subscribe(callback.getCommands(), commandObserver)
            }
            enableWakeup()
        }
    }

    internal fun enableWakeup() {
        try {

//            DDS.getInstance().agent.wakeupEngine.addMainWakeupWords(wakeupWords!!)

            DDS.getInstance().agent.wakeupEngine.setWakeupCallback {
                Log.w(TAG, "onWakeupResult = $it")

                startListening()

                JSONObject().put("greeting", greeting)
            }

            DDS.getInstance().agent.wakeupEngine.enableWakeup()

            DDS.getInstance().agent.wakeupEngine.wakeupWords.forEach {
                Log.w(TAG, it)
            }

            callback.onInitComplete()

        } catch (e: DDSNotInitCompleteException) {
            LOGE("enableWakeup $e")

            callback.onInitError("enableWakeup $e")
        }
    }

    // 关闭唤醒, 调用后将无法语音唤醒
    private fun disableWakeup() {
        try {
//            DDS.getInstance().agent.stopDialog()
            stopListening()

            DDS.getInstance().agent.wakeupEngine.disableWakeup()
        } catch (e: DDSNotInitCompleteException) {
            LOGE("disableWakeup $e")
            throw e
        }
    }

    private fun startListening() {
        if (!callback.onWakeup())
            return

        if (networkAvailable(context)) {
            LOGD("startListening -> startListening")
            speak(greeting)

            //获取识别引擎
            val asrEngine = DDS.getInstance().agent.asrEngine
            //开启识别
            try {
                asrEngine.startListening(object : ASREngine.Callback {

//                val data by lazy { ByteBuffer.allocate(1024 * 1024) }

                    override fun beginningOfSpeech() {
                        var code = TuyaIotGateway.tuyaIotUploadMediaStart()
                        LOGD(String.format("检测到用户开始说话 %d", code))
                        mIsListening = code == ERROR_OK
                        callback.onSpeechBeginning(code)
//                    data.clear()

                    }

                    override fun bufferReceived(buffer: ByteArray) {
                        if (!mIsListening)
                            return
                        LOGD(String.format("用户说话的音频数据 len %d code %d", buffer.size, TuyaIotGateway.tuyaIotUploadMedia(buffer)))

                        //超出语音数据限制 主动结束
//                    LOGD("buffer ${buffer.size} / remaining ${data.remaining()}")
//                    if (buffer.size > data.remaining()) {
//
////                        callback.onSpeechEnd(data.array())
//
////                        asrEngine.cancel()
//                        return
//                    }
//
//                    data.put(buffer)

                    }

                    override fun endOfSpeech() {
                        LOGD("检测到用户结束说话")

//                    val array = ByteArray(data.remaining())
//                    data.rewind()
//                    data.get(array, 0, array.size)

//                    val pcmFile = File("/sdcard/speech" + File.separator + "shot_" + System.currentTimeMillis() + ".pcm")
//                    val outputStream = FileOutputStream(pcmFile)
//                    outputStream.write(array)
//                    outputStream.flush()
//                    outputStream.close()

                        val errCode = TuyaIotGateway.tuyaIotUploadMediaStop()

                        if (mIsListening)
                            callback.onSpeechEnd(errCode)
                        else
                            callback.onSpeechEnd(ERROR_NETWORK_ERROR)

                        mSpeechHandler?.sendEmptyMessageDelayed(OnSpeechCallback.TIMEOUT_MSG_WHAT, timeout.toLong())

                        mIsListening = false

                    }

                    override fun partialResults(results: String) {
                        LOGD("实时识别结果反馈:$results")
                    }

                    override fun finalResults(results: String) {
                        LOGD("最终识别结果反馈:$results")
                    }

                    override fun error(error: String) {
                        LOGD("识别过程中发生的错误")
                        callback.onASRError(error)
                    }

                    override fun rmsChanged(rmsdB: Float) {
                        LOGD("用户说话的音量分贝")
                    }
                })

                callback.onStartListening()

            } catch (e: DDSNotInitCompleteException) {
                callback.onInitError("startListening: $e")
            }

        } else {
            LOGD("startListening -> startDialog")
            try {
                stopListening()
            } catch (e: Exception) {
                callback.onInitError("stopListening fail: $e")
            }
            val speakText = JSONObject()
            speakText.put("speakText", greeting)
            DDS.getInstance().agent.startDialog(speakText)
            callback.onStartListening()
        }

    }

    private fun stopListening() {
        try {
            //获取识别引擎
            val asrEngine = DDS.getInstance().agent.asrEngine
            //主动结束此次识别
            asrEngine.stopListening()
        } catch (e: DDSNotInitCompleteException) {
            LOGE("stopListening $e")
            throw e
        }

    }

    fun start() {
//        DDS.getInstance().setDebugMode(2)

        val ddsConfig = createConfig(configPth)

        mSpeechThread = HandlerThread("speech")
        mSpeechThread?.start()
        mSpeechHandler = object : Handler(mSpeechThread?.getLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    OnSpeechCallback.SUCCESS_MSG_WHAT -> callback.onResponse(true, msg.obj as java.util.ArrayList<String>)
                    OnSpeechCallback.TIMEOUT_MSG_WHAT -> callback.onResponse(false, null)
                }
            }
        }

        if (!EasyPermissions.hasPermissions(context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                        , Manifest.permission.READ_EXTERNAL_STORAGE
                        , Manifest.permission.RECORD_AUDIO)) {

            PermissionUtilAct.request(context, object : EasyPermissions.PermissionCallbacks {
                override fun onRequestPermissionsResult(p0: Int, p1: Array<out String>, p2: IntArray) {

                }

                override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
                    callback.onPermissionDenied()
                }

                override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
                    DDS.getInstance().init(context, ddsConfig, mInitListener, mAuthListener)
                }
            }, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    , Manifest.permission.READ_EXTERNAL_STORAGE
                    , Manifest.permission.RECORD_AUDIO)
        } else {
            try {
                DDS.getInstance().init(context, ddsConfig, mInitListener, mAuthListener)
            } catch (e: Exception) {
                callback.onInitError("start: $e")
            }
        }
    }

    fun stop() {
        try {
            if (DDS.getInstance().isInitComplete && DDS.getInstance().isAuthSuccess) {
                DDS.getInstance().agent.unSubscribe(messageObserver)
                DDS.getInstance().agent.unSubscribe(commandObserver)
                disableWakeup()
                DDS.getInstance().releaseSync()

                callback.onDeInitComplete()
            } else {
                callback.onDeInitError("stop fail: isInitComplete(${DDS.getInstance().isInitComplete}) isAuthSuccess(${DDS.getInstance().isAuthSuccess})")
            }
        } catch (e: Exception) {
            callback.onDeInitError("stop fail: $e")
        }

        if (mSpeechHandler != null) {
            mSpeechHandler!!.removeCallbacksAndMessages(null)
        }

        if (mSpeechThread != null) {
            mSpeechThread!!.quitSafely()
        }
    }

    public fun speak(content: String) {
        DDS.getInstance().agent.ttsEngine.speak(content, 1, "100", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }

    private fun getNetConnType(context: Context): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return run {
            if (VERSION.SDK_INT >= 28) {
                val activeNetwork = connectivityManager.activeNetwork ?: return "none"
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                        ?: return "none"

                val networkMap = mapOf(0 to "gprs", 1 to "wifi", 2 to "bluetooth", 3 to "ethernet", 4 to "vpn")
                val type = networkMap.entries.find { entry -> networkCapabilities.hasTransport(entry.key) }
                if (type != null) {
                    return type.value
                }

            } else {
                val activeNetInfo = connectivityManager.activeNetworkInfo
                if (activeNetInfo == null || !activeNetInfo.isAvailable || !activeNetInfo.isConnectedOrConnecting) {
                    return "none"
                }
                when (activeNetInfo.type) {
                    0 -> return "gprs"
                    1 -> return "wifi"
                    7 -> return "bluetooth"
                    9 -> return "ethernet"
                    17 -> return "vpn"
                }
            }
            "unknown"
        }
    }

    private fun networkAvailable(context: Context?): Boolean {
        return getNetConnType(context!!) != "none"
    }

    public fun getHandler(): Handler? {
        return mSpeechHandler
    }

    public fun setMainWakeupWord(pinyin: String?, word: String?, threshold: String?, greeting: String) {
        val mainWord = WakeupWord()
                .setPinyin(pinyin)
                .setWord(word)
                .setThreshold(threshold)
                .addGreeting(greeting)
        this.greeting = greeting
        try {
            DDS.getInstance().agent.wakeupEngine.addMainWakeupWord(mainWord)
        } catch (e: DDSNotInitCompleteException) {
            e.printStackTrace()
        }
    }

    @Throws(java.lang.Exception::class)
    fun createConfig(configPath: String): DDSConfig? {
        val config = DDSConfig()

        val open = context.assets.open("config.json")
        val configJson = IOUtils.toString(open, null)
        val configMap = JSON.parseObject(configJson)
        for (key in configMap.keys) {
            config.addConfig(key, configMap.getString(key))
        }

//        val configFile = File(configPath + "config.json")
//        if (configFile.exists()) {
//            val configJson = FileUtils.readFileToString(configFile)
//            val configMap = JSON.parseObject(configJson)
//            for (key in configMap.keys) {
//                config.addConfig(key, configMap.getString(key))
//            }
//        }
        // 基础配置项
//        config.addConfig(DDSConfig.K_PRODUCT_ID, SPEECH_PRODUCT_ID); // 产品ID -- 必填
//        config.addConfig(DDSConfig.K_USER_ID, SPEECH_USER_ID) // 用户ID -- 必填
//        config.addConfig(DDSConfig.K_PRODUCT_KEY, SPEECH_PRODUCT_KEY) // Product Key -- 必填
//        config.addConfig(DDSConfig.K_PRODUCT_SECRET, SPEECH_PRODUCT_SECRET) // Product Secre -- 必填
//        config.addConfig(DDSConfig.K_API_KEY, SPEECH_API_KEY) // 产品授权秘钥，服务端生成，用于产品授权 -- 必填
        config.addConfig(DDSConfig.K_ALIAS_KEY, "prod") // 产品的发布分支 -- 必填
        config.addConfig(DDSConfig.K_DEVICE_ID, deviceId)
        config.addConfig(DDSConfig.K_WAKEUP_ROUTER, "partner")
        config.addConfig(DDSConfig.K_MIC_TYPE, 5)
        config.addConfig("ENABLE_DYNAMIC_PORT", "true")
        // 资源更新配置项
        config.addConfig(DDSConfig.K_DUICORE_ZIP, "duicore.zip") //预置在指定目录下的DUI内核资源包名, 避免在线下载内核消耗流量, 推荐使用
        config.addConfig(DDSConfig.K_CUSTOM_ZIP, "custom.zip") // 预置在指定目录下的DUI产品配置资源包名, 避免在线下载产品配置消耗流量, 推荐使用
        if (File(configPath + "wakeup.bin").exists()) {
            config.addConfig(DDSConfig.K_WAKEUP_BIN, configPath + "wakeup.bin")
        }
//      DDS.getInstance().setDebugMode(1);
        return config
    }

}
