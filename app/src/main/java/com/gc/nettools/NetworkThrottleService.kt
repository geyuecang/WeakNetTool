package com.gc.nettools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class NetworkThrottleService : Service() {
    
    companion object {
        private const val TAG = "NetworkThrottleService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "network_throttle_channel"
        
        // 网络限速配置
        data class ThrottleConfig(
            val downloadSpeedKbps: Int,
            val uploadSpeedKbps: Int,
            val latencyMs: Int
        )
        
        val NORMAL_CONFIG = ThrottleConfig(100000, 50000, 20) // 100Mbps down, 50Mbps up
        val SLOW_CONFIG = ThrottleConfig(500, 200, 500) // 0.5Mbps down, 0.2Mbps up
        val VERY_SLOW_CONFIG = ThrottleConfig(100, 50, 1000) // 0.1Mbps down, 0.05Mbps up
        val OFFLINE_CONFIG = ThrottleConfig(0, 0, 9999)
    }
    
    private val binder = LocalBinder()
    private var currentConfig = NORMAL_CONFIG
    private var isThrottling = false
    private val executor = Executors.newFixedThreadPool(4)
    private val downloadSemaphore = Semaphore(1)
    private val uploadSemaphore = Semaphore(1)
    
    // 网络状态监听
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")
        }
        
        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            Log.d(TAG, "Network capabilities changed")
        }
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): NetworkThrottleService = this@NetworkThrottleService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        registerNetworkCallback()
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        executor.shutdown()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Network Throttle Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Network throttling service"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("网络限速服务")
            .setContentText("当前限速: ${currentConfig.downloadSpeedKbps} kbps")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
    
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }
    
    private fun unregisterNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
    
    fun setThrottleConfig(config: ThrottleConfig) {
        currentConfig = config
        isThrottling = config != NORMAL_CONFIG
        updateNotification()
        Log.d(TAG, "Throttle config set: $config")
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
    
    // 限速下载
    suspend fun throttledDownload(url: String, timeoutMs: Long = 10000): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs.toInt()
                connection.readTimeout = timeoutMs.toInt()
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP error code: $responseCode")
                }
                
                val inputStream = connection.inputStream
                val contentLength = connection.contentLength
                
                if (contentLength > 0) {
                    val buffer = ByteArray(contentLength)
                    var bytesRead = 0
                    var totalBytesRead = 0
                    
                    while (totalBytesRead < contentLength && bytesRead != -1) {
                        // 应用限速
                        if (currentConfig.downloadSpeedKbps > 0) {
                            val bytesToRead = minOf(
                                contentLength - totalBytesRead,
                                currentConfig.downloadSpeedKbps * 1024 / 8 / 10 // 每100ms的字节数
                            )
                            
                            bytesRead = inputStream.read(buffer, totalBytesRead, bytesToRead.toInt())
                            if (bytesRead > 0) {
                                totalBytesRead += bytesRead
                                delay(100) // 100ms延迟
                            }
                        } else {
                            // 离线模式
                            delay(1000)
                            break
                        }
                    }
                    
                    inputStream.close()
                    buffer.copyOf(totalBytesRead)
                } else {
                    // 未知内容长度，读取所有数据
                    val buffer = mutableListOf<Byte>()
                    val tempBuffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (inputStream.read(tempBuffer).also { bytesRead = it } != -1) {
                        if (currentConfig.downloadSpeedKbps > 0) {
                            val bytesToRead = minOf(
                                bytesRead,
                                currentConfig.downloadSpeedKbps * 1024 / 8 / 10
                            )
                            buffer.addAll(tempBuffer.take(bytesToRead))
                            delay(100)
                        } else {
                            delay(1000)
                            break
                        }
                    }
                    
                    inputStream.close()
                    buffer.toByteArray()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                throw e
            }
        }
    }
    
    // 限速上传
    suspend fun throttledUpload(url: String, data: ByteArray, timeoutMs: Long = 10000): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs.toInt()
                connection.readTimeout = timeoutMs.toInt()
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setRequestProperty("Content-Length", data.size.toString())
                
                val outputStream = connection.outputStream
                var bytesWritten = 0
                
                while (bytesWritten < data.size) {
                    if (currentConfig.uploadSpeedKbps > 0) {
                        val bytesToWrite = minOf(
                            data.size - bytesWritten,
                            currentConfig.uploadSpeedKbps * 1024 / 8 / 10
                        )
                        
                        outputStream.write(data, bytesWritten, bytesToWrite)
                        bytesWritten += bytesToWrite
                        delay(100)
                    } else {
                        delay(1000)
                        break
                    }
                }
                
                outputStream.close()
                val responseCode = connection.responseCode
                responseCode in 200..299
            } catch (e: Exception) {
                Log.e(TAG, "Upload error", e)
                false
            }
        }
    }
    
    // 获取当前配置
    fun getCurrentConfig(): ThrottleConfig = currentConfig
    
    // 检查是否正在限速
    fun isThrottling(): Boolean = isThrottling
} 