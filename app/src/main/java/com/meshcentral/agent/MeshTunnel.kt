package com.meshcentral.agent

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.provider.MediaStore
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.collections.ArrayList
import kotlin.random.Random


class PendingActivityData(tunnel: MeshTunnel, id: Int, url: Uri, where: String, args: String, req: JSONObject) {
    var tunnel : MeshTunnel = tunnel
    var id : Int = id
    var url : Uri = url
    var where : String = where
    var args : String = args
    var req : JSONObject = req
}

class MeshTunnel(parent: MeshAgent, url: String, serverData: JSONObject) : WebSocketListener() {
    private var parent : MeshAgent = parent
    private var url:String = url
    private var serverData: JSONObject = serverData
    private var _webSocket: WebSocket? = null
    private var serverTlsCertHash: ByteArray? = null
    private var connectionTimer: CountDownTimer? = null
    private var state: Int = 0
    private var usage: Int = 0
    private var tunnelOptions : JSONObject? = null
    private var lastDirRequest : JSONObject? = null
    private var fileUpload : OutputStream? = null
    private var fileUploadName : String? = null
    private var fileUploadReqId : Int = 0
    private var fileUploadSize : Int = 0

    init {
        //println("MeshTunnel Init: ${serverData.toString()}")
        var serverTlsCertHashHex = serverData.optString("servertlshash")
        if (serverTlsCertHashHex != null) { serverTlsCertHash = parent.hexToByteArray(
                serverTlsCertHashHex
        ) }
        //var tunnelUsage = serverData.getInt("usage")
        //var tunnelUser = serverData.getString("username")

        //println("Starting tunnel: $url")
        //println("Tunnel usage: $tunnelUsage")
        //println("Tunnel user: $tunnelUser")
        startSocket()
    }

    fun Stop() {
        //println("MeshTunnel Stop")
        stopSocket()
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
            ) {
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                var hash =
                        MessageDigest.getInstance("SHA-384").digest(chain?.get(0)?.encoded).toHex()
                if ((serverTlsCertHash != null) && (hash.equals(serverTlsCertHash?.toHex()))) return
                if (hash.equals(parent.serverTlsCertHash?.toHex())) return
                println("Got Bad Tunnel TlsHash: ${hash}")
                throw CertificateException()
            }

            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })

        // Install the special trust manager that records the certificate hash of the server
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.MINUTES)
                .writeTimeout(60, TimeUnit.MINUTES)
                .hostnameVerifier ( hostnameVerifier = HostnameVerifier{ _, _ -> true })
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .build()
    }


    fun startSocket() {
        _webSocket = getUnsafeOkHttpClient().newWebSocket(
                Request.Builder().url(url).build(),
                this
        )
    }

    fun stopSocket() {
        // Disconnect and clean the relay socket
        if (_webSocket != null) {
            try {
                _webSocket?.close(NORMAL_CLOSURE_STATUS, null)
                _webSocket = null
            } catch (ex: Exception) { }
        }
        // Clear the connection timer
        if (connectionTimer != null) {
            connectionTimer?.cancel()
            connectionTimer = null
        }
        // Remove the tunnel from the parent's list
        parent.removeTunnel(this) // Notify the parent that this tunnel is done
    }

    companion object {
        const val NORMAL_CLOSURE_STATUS = 1000
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        //println("Tunnel-onOpen")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        //println("Tunnnel-onMessage: $text")
        if (state == 0) {
            if ((text == "c") || (text == "cr")) { state = 1; }
            return
        }
        else if (state == 1) {
            // {"type":"options","file":"Images/1104105516.JPG"}
            if (text.startsWith('{')) {
                var json = JSONObject(text)
                var type = json.optString("type")
                if (type == "options") { tunnelOptions = json }
            } else {
                var xusage = text.toInt()
                if (((xusage < 1) || (xusage > 5)) && (xusage != 10)) {
                    println("Invalid usage $text"); stopSocket(); return
                }
                var serverExpectedUsage = serverData.optInt("usage")
                if ((serverExpectedUsage != null) && (serverExpectedUsage != xusage) && (serverExpectedUsage == null)) {
                    println("Unexpected usage $text != $serverExpectedUsage");
                    stopSocket(); return
                }
                usage = xusage;
                state = 2

                // Start the connection time except if this is a file transfer
                if (usage != 10) {
                    //println("Connected usage $usage")
                    startConnectionTimer()
                } else {
                    if (tunnelOptions == null) {
                        println("No file transfer options");
                        stopSocket();
                    } else {
                        var filename = tunnelOptions?.optString("file")
                        if (filename == null) {
                            println("No file transfer name");
                            stopSocket();
                        } else {
                            //println("File transfer usage")
                            startFileTransfer(filename)
                        }
                    }
                }
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, msg: ByteString) {
        //println("Tunnel-onBinaryMessage: ${msg.size}, ${msg.toByteArray().toHex()}")
        if ((state != 2) || (msg.size < 2)) return;
        try {
            if (msg[0].toInt() == 123) {
                // If we are authenticated, process JSON data
                processTunnelData(String(msg.toByteArray(), Charsets.UTF_8))
            } else if (fileUpload != null) {
                // If this is file upload data, process it here
                if (msg[0].toInt() == 0) {
                    // If data starts with zero, skip the first byte. This is used to escape binary file data from JSON.
                    fileUploadSize += (msg.size - 1);
                    var buf = msg.toByteArray()
                    try {
                        fileUpload?.write(buf, 1, buf.size - 1)
                    } catch (ex : Exception) {
                        // Report a problem
                        val json = JSONObject()
                        json.put("action", "uploaderror")
                        json.put("reqid", fileUploadReqId)
                        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
                        try { fileUpload?.close() } catch (ex : Exception) {}
                        fileUpload = null
                        return
                    }
                } else {
                    // If data does not start with zero, save as-is.
                    fileUploadSize += msg.size;
                    try {
                        fileUpload?.write(msg.toByteArray())
                    } catch (ex : Exception) {
                        // Report a problem
                        val json = JSONObject()
                        json.put("action", "uploaderror")
                        json.put("reqid", fileUploadReqId)
                        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
                        try { fileUpload?.close() } catch (ex : Exception) {}
                        fileUpload = null
                        return
                    }
                }

                // Ask for more data
                val json = JSONObject()
                json.put("action", "uploadack")
                json.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
            }
        }
        catch (e: Exception) {
            println("Tunnel-Exception: ${e.toString()}")
        }
    }

    // Cause some data to be sent over the websocket control channel every 2 minutes to keep it open
    private fun startConnectionTimer() {
        parent.parent.runOnUiThread {
            connectionTimer = object: CountDownTimer(120000000, 120000) {
                override fun onTick(millisUntilFinished: Long) {
                    if (_webSocket != null) {
                        _webSocket?.send(ByteArray(1).toByteString()) // If not, sent a single zero byte
                    }
                }
                override fun onFinish() { startConnectionTimer() }
            }
            connectionTimer?.start()
        }
    }

    private fun processTunnelData(jsonStr: String) {
        //println("JSON: $jsonStr")
        val json = JSONObject(jsonStr)
        var action = json.getString("action")
        //println("action: $action")
        when (action) {
            "ls" -> {
                val path = json.getString("path")
                if (path == "") {
                    var r: JSONArray = JSONArray()
                    r.put(JSONObject("{n:\"Images\",t:2}"))
                    r.put(JSONObject("{n:\"Audio\",t:2}"))
                    r.put(JSONObject("{n:\"Videos\",t:2}"))
                    //r.put(JSONObject("{n:\"Documents\",t:2}"))
                    json.put("dir", r)
                } else {
                    lastDirRequest = json; // Bit of a hack, but use this to refresh after a file delete
                    json.put("dir", getFolder(path))
                }
                if (_webSocket != null) {
                    _webSocket?.send(json.toString().toByteArray(Charsets.UTF_8).toByteString())
                }
            }
            "rm" -> {
                val path = json.getString("path")
                val filenames = json.getJSONArray("delfiles")
                deleteFile(path, filenames, json)
            }
            "upload" -> {
                // {"action":"upload","reqid":0,"path":"Images","name":"00000000.JPG","size":1180231}
                val path = json.getString("path")
                val name = json.getString("name")
                val size = json.getInt("size")
                val reqid = json.getInt("reqid")

                // Close previous upload
                if (fileUpload != null) {
                    fileUpload?.close()
                    fileUpload = null;
                }

                // Setup
                fileUploadName = name
                fileUploadReqId = reqid
                fileUploadSize = 0

                // Open a output file stream
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver: ContentResolver = parent.parent.getContentResolver()
                    val contentValues = ContentValues()
                    contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    if (name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg")) {
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
                        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                    if (name.toLowerCase().endsWith(".png")) {
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                    if (name.toLowerCase().endsWith(".bmp")) {
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/bmp")
                        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                    if (name.toLowerCase().endsWith(".mp4")) {
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    }
                    if (name.toLowerCase().endsWith(".mp3")) {
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg3")
                        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                    }
                    if (name.toLowerCase().endsWith(".ogg")) {
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg")
                        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                    }
                    val fileUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    fileUpload = resolver.openOutputStream(Objects.requireNonNull(fileUri)!!)
                } else {
                    //val fileDir: String = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
                    val fileDir: String = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString()
                    val file = File(fileDir, name)
                    fileUpload = FileOutputStream(file)
                }

                // Send response
                val json = JSONObject()
                json.put("action", "uploadstart")
                json.put("reqid", reqid)
                if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }
            }
            "uploaddone" -> {
                if (fileUpload == null) return;
                fileUpload?.close()
                fileUpload = null;

                // Send response
                val json = JSONObject()
                json.put("action", "uploaddone")
                json.put("reqid", fileUploadReqId)
                if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }

                // Event the server
                var eventArgs = JSONArray()
                eventArgs.put(fileUploadName)
                eventArgs.put(fileUploadSize)
                parent.logServerEventEx(105, eventArgs, "Upload: \"${fileUploadName}}\", Size: $fileUploadSize", serverData);
            }
            else -> {
                // Unknown command, ignore it.
                println("Unhandled action: $action, $jsonStr")
            }
        }
    }

    // https://developer.android.com/training/data-storage/shared/media
    fun getFolder(dir: String) : JSONArray {
        val r : JSONArray = JSONArray()
        val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
        )
        var uri : Uri? = null;
        if (dir.equals("Images")) { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (dir.equals("Audio")) { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (dir.equals("Videos")) { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (dir == "Documents") { uri = MediaStore.Files. }
        if (uri == null) { return r }

        val cursor: Cursor? = parent.parent.getContentResolver().query(
                uri,
                projection,
                null,
                null,
                null
        )
        if (cursor != null) {
            val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateModified: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            //val typeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                var f : JSONObject = JSONObject()
                f.put("n", cursor.getString(titleColumn))
                f.put("t", 3)
                f.put("s", cursor.getInt(sizeColumn))
                f.put("d", cursor.getInt(dateModified))
                r.put(f)
                //println("${cursor.getString(titleColumn)}, ${cursor.getString(typeColumn)}")
            }
        }
        return r;
    }

    fun deleteFile(path: String, filenames: JSONArray, req: JSONObject) {
        var fileArray:ArrayList<String> = ArrayList<String>()
        for (i in 0 until filenames.length()) { fileArray.add(filenames.getString(i)) }

        val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        )
        var uri : Uri? = null;
        if (path.equals("Images")) { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (path.equals("Audio")) { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (path.equals("Videos")) { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (filenameSplit[0] == "Documents") { uri = MediaStore.Files. }
        if (uri == null) return

        val cursor: Cursor? = parent.parent.getContentResolver().query(
                uri,
                projection,
                null,
                null,
                null
        )
        if (cursor != null) {
            val idColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            //val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            var fileidArray:ArrayList<String> = ArrayList<String>()
            var fileUriArray:ArrayList<Uri> = ArrayList<Uri>()
            while (cursor.moveToNext()) {
                var name = cursor.getString(titleColumn)
                if (fileArray.contains(name)) {
                    var id = cursor.getString(idColumn)
                    var contentUrl: Uri = ContentUris.withAppendedId(uri, cursor.getLong(idColumn))
                    //var fileSize = cursor.getInt(sizeColumn)
                    println("addid: $name, uri: $contentUrl")
                    fileidArray.add(id)
                    fileUriArray.add(contentUrl)
                }
            }
            /*
            if (fileUriArray.count() > 1) {
                println("w1")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    println("w2")
                    var pi: PendingIntent = MediaStore.createDeleteRequest(parent.parent.getContentResolver(), fileUriArray);

                    println("w3")
                    try {
                        startIntentSenderForResult(parent.parent, pi.intentSender, DELETE_PERMISSION_REQUEST, null, 0, 0, 0, null);
                        println("w4")
                    } catch (ex: IntentSender.SendIntentException) {
                    }
                }
            }
            */
            if (fileidArray.count() == 1) {
                try {
                    parent.parent.contentResolver.delete(fileUriArray[0], "${MediaStore.Images.Media._ID} = ?", arrayOf(fileidArray[0]))
                    fileDeleteResponse(req, true) // Send success
                } catch (securityException: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val recoverableSecurityException =
                                securityException as? RecoverableSecurityException
                                        ?: throw securityException

                        // Save the activity
                        var activityCode = Random.nextInt() and 0xFFFF
                        var pad = PendingActivityData(this, activityCode, fileUriArray[0], "${MediaStore.Images.Media._ID} = ?", fileidArray[0], req)
                        pendingActivities.add(pad)

                        // Launch the activity
                        val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                        parent.parent.startIntentSenderForResult(
                                intentSender,
                                activityCode,
                                null,
                                0,
                                0,
                                0,
                                null
                        )
                    } else {
                        fileDeleteResponse(req, false) // Send fail
                    }
                }
            }
        }
    }

    fun deleteFileEx(pad: PendingActivityData) {
        try {
            parent.parent.contentResolver.delete(pad.url, pad.where, arrayOf(pad.args))
            fileDeleteResponse(pad.req, true) // Send success
        } catch (ex: Exception) {
            fileDeleteResponse(pad.req, false) // Send fail
        }
    }

    fun startFileTransfer(filename: String) {
        var filenameSplit = filename.split('/')
        if (filenameSplit.count() != 2) { stopSocket(); return }
        //println("startFileTransfer: $filenameSplit")

        val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        )
        var uri : Uri? = null;
        if (filenameSplit[0].equals("Images")) { uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI }
        if (filenameSplit[0].equals("Audio")) { uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI }
        if (filenameSplit[0].equals("Videos")) { uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        //if (filenameSplit[0] == "Documents") { uri = MediaStore.Files. }
        if (uri == null) { stopSocket(); return }

        val cursor: Cursor? = parent.parent.getContentResolver().query(
                uri,
                projection,
                null,
                null,
                null
        )
        if (cursor != null) {
            val idColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn: Int = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                var name = cursor.getString(titleColumn)
                if (name == filenameSplit[1]) {
                    var contentUrl: Uri = ContentUris.withAppendedId(uri, cursor.getLong(idColumn))
                    var fileSize = cursor.getInt(sizeColumn)

                    // Event to the server
                    var eventArgs = JSONArray()
                    eventArgs.put(filename)
                    eventArgs.put(fileSize)
                    parent.logServerEventEx(106, eventArgs, "Download: ${filename}, Size: $fileSize", serverData);

                    // Serve the file
                    parent.parent.getContentResolver().openInputStream(contentUrl).use { stream ->
                        // Perform operation on stream
                        var buf = ByteArray(65535)
                        var len : Int
                        while (true) {
                            len = stream!!.read(buf, 0, 65535)
                            if (len <= 0) { stopSocket(); break; } // Stream is done
                            if (_webSocket == null) { stopSocket(); break; } // Web socket closed
                            _webSocket?.send(buf.toByteString(0, len))
                            if (_webSocket?.queueSize()!! > 655350) { Thread.sleep(100)}
                        }
                    }
                    return;
                }
            }
        }
        stopSocket()
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        //println("Tunnel-onClosing")
        stopSocket()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        println("Tunnel-onFailure ${t.toString()},  ${response.toString()}")
        stopSocket()
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    fun fileDeleteResponse(req: JSONObject, success: Boolean) {
        val json = JSONObject()
        json.put("action", "rm")
        json.put("reqid", req.getString("reqid"))
        json.put("success", success)
        if (_webSocket != null) { _webSocket?.send(json.toString().toByteArray().toByteString()) }

        // Event to the server
        val path = req.getString("path")
        val filenames = req.getJSONArray("delfiles")
        if (filenames.length() == 1) {
            var eventArgs = JSONArray()
            eventArgs.put(path + '/' + filenames[0])
            parent.logServerEventEx(45, eventArgs, "Delete: \"${path}/${filenames[0]}\"", serverData);
        }

        if (success && (lastDirRequest != null)) {
            val path = lastDirRequest?.getString("path")
            if ((path != null) && (path != "")) {
                lastDirRequest?.put("dir", getFolder(path!!))
                if (_webSocket != null) {_webSocket?.send(lastDirRequest?.toString()!!.toByteArray(Charsets.UTF_8).toByteString()) }
            }
        }
    }
}