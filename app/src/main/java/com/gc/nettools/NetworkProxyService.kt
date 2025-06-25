package com.gc.nettools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

class NetworkProxyService : Service() {
    
    companion object {
        private const val TAG = "NetworkProxyService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "network_proxy_channel"
        private const val PROXY_PORT = 8080
        
        // 网络限速配置
        data class ThrottleConfig(
            val downloadSpeedKbps: Int,
            val uploadSpeedKbps: Int,
            val latencyMs: Int,
            val isOffline: Boolean = false
        )
        
        val NORMAL_CONFIG = ThrottleConfig(0, 0, 0, false) // 无限制，无延迟
        val FAST_4G_CONFIG = ThrottleConfig(50000, 25000, 50, false) // 50Mbps down, 25Mbps up
        val NORMAL_4G_CONFIG = ThrottleConfig(20000, 10000, 100, false) // 20Mbps down, 10Mbps up
        val SLOW_4G_CONFIG = ThrottleConfig(5000, 2000, 200, false) // 5Mbps down, 2Mbps up
        val FAST_3G_CONFIG = ThrottleConfig(2000, 1000, 300, false) // 2Mbps down, 1Mbps up
        val NORMAL_3G_CONFIG = ThrottleConfig(1000, 500, 500, false) // 1Mbps down, 0.5Mbps up
        val SLOW_3G_CONFIG = ThrottleConfig(500, 200, 800, false) // 0.5Mbps down, 0.2Mbps up
        val FAST_2G_CONFIG = ThrottleConfig(200, 100, 1000, false) // 0.2Mbps down, 0.1Mbps up
        val NORMAL_2G_CONFIG = ThrottleConfig(100, 50, 1500, false) // 0.1Mbps down, 0.05Mbps up
        val SLOW_2G_CONFIG = ThrottleConfig(50, 20, 2000, false) // 0.05Mbps down, 0.02Mbps up
        val GPRS_CONFIG = ThrottleConfig(20, 10, 3000, false) // 0.02Mbps down, 0.01Mbps up
        val VERY_SLOW_CONFIG = ThrottleConfig(10, 5, 5000, false) // 0.01Mbps down, 0.005Mbps up
        val OFFLINE_CONFIG = ThrottleConfig(0, 0, 0, true) // 离线模式
    }
    
    private val binder = LocalBinder()
    private var currentConfig = NORMAL_CONFIG
    private var isProxyRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val proxyServer = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): NetworkProxyService = this@NetworkProxyService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "NetworkProxyService created")
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopProxy()
        executor.shutdown()
        Log.d(TAG, "NetworkProxyService destroyed")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Network Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Network proxy service for throttling"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val status = if (isProxyRunning) "运行中" else "未运行"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("网络代理服务")
            .setContentText("状态: $status, 端口: $PROXY_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
    
    fun setThrottleConfig(config: ThrottleConfig) {
        currentConfig = config
        updateNotification()
        Log.d(TAG, "Throttle config set: $config")
        
        if (isProxyRunning) {
            Log.d(TAG, "Proxy is running, updating config")
        }
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
    
    fun startProxy() {
        if (isProxyRunning) {
            Log.d(TAG, "Proxy is already running")
            return
        }
        
        Log.d(TAG, "Starting proxy server on port $PROXY_PORT")
        isProxyRunning = true
        executor.submit {
            runProxyServer()
        }
        updateNotification()
    }
    
    fun stopProxy() {
        Log.d(TAG, "Stopping proxy server")
        isProxyRunning = false
        proxyServer.set(false)
        
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        
        updateNotification()
    }
    
    private fun runProxyServer() {
        try {
            Log.d(TAG, "Attempting to create ServerSocket on port $PROXY_PORT")
            serverSocket = ServerSocket(PROXY_PORT)
            Log.d(TAG, "ServerSocket created successfully")
            
            proxyServer.set(true)
            Log.d(TAG, "Proxy server started successfully on port $PROXY_PORT")
            
            while (isProxyRunning && proxyServer.get()) {
                try {
                    Log.d(TAG, "Waiting for client connection...")
                    val clientSocket = serverSocket?.accept()
                    if (clientSocket != null) {
                        Log.d(TAG, "New client connected: ${clientSocket.inetAddress}")
                        executor.submit {
                            handleClient(clientSocket)
                        }
                    }
                } catch (e: Exception) {
                    if (isProxyRunning) {
                        Log.e(TAG, "Error accepting client", e)
                    }
                }
            }
            
            serverSocket?.close()
            Log.d(TAG, "Proxy server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting proxy server", e)
            isProxyRunning = false
            updateNotification()
            
            // 尝试使用其他端口
            if (e is java.net.BindException) {
                Log.e(TAG, "Port $PROXY_PORT is already in use, trying alternative port")
                tryAlternativePort()
            }
        }
    }
    
    private fun tryAlternativePort() {
        val alternativePorts = listOf(8888, 8889, 8890, 8891)
        
        for (port in alternativePorts) {
            try {
                Log.d(TAG, "Trying alternative port: $port")
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Successfully bound to port: $port")
                
                // 更新通知显示新端口
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("网络代理服务")
                    .setContentText("状态: 运行中, 端口: $port (原端口被占用)")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build()
                notificationManager.notify(NOTIFICATION_ID, notification)
                
                // 继续运行代理服务器
                proxyServer.set(true)
                while (isProxyRunning && proxyServer.get()) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null) {
                            Log.d(TAG, "New client connected on port $port: ${clientSocket.inetAddress}")
                            executor.submit {
                                handleClient(clientSocket)
                            }
                        }
                    } catch (e: Exception) {
                        if (isProxyRunning) {
                            Log.e(TAG, "Error accepting client on port $port", e)
                        }
                    }
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind to port $port", e)
                continue
            }
        }
    }
    
    private fun handleClient(clientSocket: Socket) {
        try {
            Log.d(TAG, "Handling client: ${clientSocket.inetAddress}")
            
            // 读取HTTP请求
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            
            val request = readHttpRequest(input)
            if (request.isEmpty()) {
                Log.d(TAG, "Empty request, closing connection")
                clientSocket.close()
                return
            }
            
            // 解析请求行
            val requestLine = request[0]
            Log.d(TAG, "Request: $requestLine")
            
            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                Log.d(TAG, "Invalid request line: $requestLine")
                clientSocket.close()
                return
            }
            
            val method = parts[0]
            val url = parts[1]
            
            if (method == "CONNECT") {
                // HTTPS代理
                Log.d(TAG, "Handling HTTPS request: $url")
                handleHttpsProxy(clientSocket, url)
            } else {
                // HTTP代理
                Log.d(TAG, "Handling HTTP request: $url")
                handleHttpProxy(clientSocket, request)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket", e)
            }
        }
    }
    
    private fun readHttpRequest(input: InputStream): List<String> {
        val reader = BufferedReader(InputStreamReader(input))
        val lines = mutableListOf<String>()
        
        var line: String?
        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            lines.add(line!!)
        }
        
        return lines
    }
    
    private fun handleHttpProxy(clientSocket: Socket, request: List<String>) {
        try {
            // 检查离线模式
            if (currentConfig.isOffline) {
                Log.d(TAG, "Offline mode: rejecting HTTP request")
                sendErrorResponse(clientSocket.getOutputStream(), "503 Service Unavailable - Offline Mode")
                return
            }
            
            val requestLine = request[0]
            val parts = requestLine.split(" ")
            val method = parts[0]
            val url = parts[1]
            
            Log.d(TAG, "Processing HTTP request: $method $url")
            
            // 解析URL
            val urlParts = url.split("://")
            if (urlParts.size != 2) {
                Log.d(TAG, "Invalid URL format: $url")
                sendErrorResponse(clientSocket.getOutputStream(), "400 Bad Request")
                return
            }
            
            val protocol = urlParts[0]
            val hostPort = urlParts[1].split("/", limit = 2)
            val host = hostPort[0]
            val path = if (hostPort.size > 1) "/${hostPort[1]}" else "/"
            
            Log.d(TAG, "Connecting to: $host, path: $path")
            
            // 连接目标服务器
            val hostParts = host.split(":")
            val targetHost = hostParts[0]
            val targetPort = if (hostParts.size > 1) hostParts[1].toInt() else 80
            
            val targetSocket = Socket(targetHost, targetPort)
            targetSocket.soTimeout = 30000 // 30秒超时
            Log.d(TAG, "Connected to target: $targetHost:$targetPort")
            
            // 应用延迟
            if (currentConfig.latencyMs > 0) {
                Log.d(TAG, "Applying latency: ${currentConfig.latencyMs}ms")
                Thread.sleep(currentConfig.latencyMs.toLong())
            }
            
            // 发送请求到目标服务器
            val targetOutput = targetSocket.getOutputStream()
            val targetInput = targetSocket.getInputStream()
            
            // 构建新的请求
            val newRequest = StringBuilder()
            newRequest.append("$method $path HTTP/1.1\r\n")
            
            // 添加请求头
            for (i in 1 until request.size) {
                val header = request[i]
                if (!header.startsWith("Host:") && !header.startsWith("Connection:")) {
                    newRequest.append("$header\r\n")
                }
            }
            newRequest.append("Host: $host\r\n")
            newRequest.append("Connection: close\r\n")
            newRequest.append("\r\n")
            
            Log.d(TAG, "Sending request to target:\n${newRequest}")
            targetOutput.write(newRequest.toString().toByteArray())
            targetOutput.flush()
            
            // 如果是POST请求，发送请求体
            if (method == "POST") {
                val clientInput = clientSocket.getInputStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (clientInput.read(buffer).also { bytesRead = it } != -1) {
                    targetOutput.write(buffer, 0, bytesRead)
                }
                targetOutput.flush()
            }
            
            // 读取响应并发送给客户端
            val clientOutput = clientSocket.getOutputStream()
            val responseBuffer = ByteArray(8192)
            var bytesRead = 0
            var totalBytesRead = 0L
            var isFirstChunk = true
            
            Log.d(TAG, "Reading response from target...")
            while (targetInput.read(responseBuffer).also { bytesRead = it } != -1) {
                totalBytesRead += bytesRead
                
                // 应用下载限速
                if (currentConfig.downloadSpeedKbps > 0) {
                    val delayMs = (bytesRead * 8 * 1000L) / (currentConfig.downloadSpeedKbps * 1024)
                    if (delayMs > 0) {
                        Thread.sleep(delayMs)
                    }
                }
                
                // 确保数据被完全写入
                clientOutput.write(responseBuffer, 0, bytesRead)
                clientOutput.flush()
            }
            
            Log.d(TAG, "HTTP response sent, total bytes: $totalBytesRead")
            targetSocket.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in HTTP proxy", e)
            try {
                sendErrorResponse(clientSocket.getOutputStream(), "500 Internal Server Error")
            } catch (ex: Exception) {
                Log.e(TAG, "Error sending error response", ex)
            }
        }
    }
    
    private fun handleHttpsProxy(clientSocket: Socket, url: String) {
        var targetSocket: Socket? = null
        try {
            // 检查离线模式
            if (currentConfig.isOffline) {
                Log.d(TAG, "Offline mode: rejecting HTTPS request")
                sendErrorResponse(clientSocket.getOutputStream(), "503 Service Unavailable - Offline Mode")
                return
            }
            
            val hostPort = url.split(":")
            val host = hostPort[0]
            val port = if (hostPort.size > 1) hostPort[1].toInt() else 443
            
            Log.d(TAG, "Connecting to HTTPS: $host:$port")
            
            // 连接目标服务器
            targetSocket = Socket(host, port)
            targetSocket.soTimeout = 30000 // 30秒超时
            Log.d(TAG, "Connected to HTTPS target: $host:$port")
            
            // 发送连接成功响应
            val output = clientSocket.getOutputStream()
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            
            // 创建双向数据流
            val clientInput = clientSocket.getInputStream()
            val clientOutput = clientSocket.getOutputStream()
            val targetInput = targetSocket.getInputStream()
            val targetOutput = targetSocket.getOutputStream()
            
            // 使用原子布尔值来协调两个线程
            val connectionClosed = AtomicBoolean(false)
            
            // 客户端到服务器（上传）
            val uploadFuture = executor.submit {
                try {
                    val buffer = ByteArray(8192)
                    var bytesRead = 0
                    var totalBytesRead = 0L
                    
                    while (!connectionClosed.get() && clientInput.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesRead += bytesRead
                        
                        // 应用上传限速
                        if (currentConfig.uploadSpeedKbps > 0) {
                            val delayMs = (bytesRead * 8 * 1000L) / (currentConfig.uploadSpeedKbps * 1024)
                            if (delayMs > 0) {
                                Thread.sleep(delayMs)
                            }
                        }
                        
                        try {
                            targetOutput.write(buffer, 0, bytesRead)
                            targetOutput.flush()
                        } catch (e: Exception) {
                            Log.d(TAG, "Target socket closed during upload, stopping upload stream")
                            break
                        }
                    }
                    
                    Log.d(TAG, "HTTPS upload completed, total bytes: $totalBytesRead")
                } catch (e: Exception) {
                    if (e is java.net.SocketException && e.message?.contains("Socket closed") == true) {
                        Log.d(TAG, "Client socket closed during upload")
                    } else {
                        Log.e(TAG, "Error in client to target stream", e)
                    }
                } finally {
                    connectionClosed.set(true)
                }
            }
            
            // 服务器到客户端（下载）
            val downloadFuture = executor.submit {
                try {
                    val buffer = ByteArray(8192)
                    var bytesRead = 0
                    var totalBytesRead = 0L
                    
                    while (!connectionClosed.get() && targetInput.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesRead += bytesRead
                        
                        // 应用下载限速
                        if (currentConfig.downloadSpeedKbps > 0) {
                            val delayMs = (bytesRead * 8 * 1000L) / (currentConfig.downloadSpeedKbps * 1024)
                            if (delayMs > 0) {
                                Thread.sleep(delayMs)
                            }
                        }
                        
                        try {
                            clientOutput.write(buffer, 0, bytesRead)
                            clientOutput.flush()
                        } catch (e: Exception) {
                            Log.d(TAG, "Client socket closed during download, stopping download stream")
                            break
                        }
                    }
                    
                    Log.d(TAG, "HTTPS download completed, total bytes: $totalBytesRead")
                } catch (e: Exception) {
                    if (e is java.net.SocketException && e.message?.contains("Socket closed") == true) {
                        Log.d(TAG, "Target socket closed during download")
                    } else {
                        Log.e(TAG, "Error in target to client stream", e)
                    }
                } finally {
                    connectionClosed.set(true)
                }
            }
            
            // 等待任一方向完成
            try {
                uploadFuture.get(60, TimeUnit.SECONDS) // 60秒超时
            } catch (e: Exception) {
                Log.d(TAG, "Upload stream completed or timed out")
            }
            
            try {
                downloadFuture.get(60, TimeUnit.SECONDS) // 60秒超时
            } catch (e: Exception) {
                Log.d(TAG, "Download stream completed or timed out")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in HTTPS proxy", e)
            try {
                sendErrorResponse(clientSocket.getOutputStream(), "500 Internal Server Error")
            } catch (ex: Exception) {
                Log.e(TAG, "Error sending error response", ex)
            }
        } finally {
            try {
                targetSocket?.close()
            } catch (e: Exception) {
                Log.d(TAG, "Error closing target socket", e)
            }
        }
    }
    
    private fun sendErrorResponse(output: OutputStream, status: String) {
        try {
            val response = """
                HTTP/1.1 $status
                Content-Type: text/plain
                Content-Length: 0
                
            """.trimIndent()
            output.write(response.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending error response", e)
        }
    }
    
    fun getCurrentProxyPort(): Int {
        return serverSocket?.localPort ?: PROXY_PORT
    }
    
    fun getCurrentConfig(): ThrottleConfig = currentConfig
    
    fun isProxyRunning(): Boolean = isProxyRunning
    
    fun getProxyPort(): Int = PROXY_PORT
} 