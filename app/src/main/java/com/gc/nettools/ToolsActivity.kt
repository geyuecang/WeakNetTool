package com.gc.nettools

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random

class ToolsActivity : AppCompatActivity() {
    
    private lateinit var networkLevelGroup: RadioGroup
    private lateinit var applyNetworkButton: Button
    private lateinit var downloadSpeedText: TextView
    private lateinit var uploadSpeedText: TextView
    private lateinit var pingText: TextView
    private lateinit var startSpeedTestButton: Button
    private lateinit var stopSpeedTestButton: Button
    private lateinit var networkStatusText: TextView
    private lateinit var proxyStatusText: TextView
    private lateinit var setupProxyButton: Button
    private lateinit var networkDescriptionText: TextView
    
    private var isSpeedTestRunning = false
    private var speedTestJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)
    
    // 网络代理服务相关
    private var networkProxyService: NetworkProxyService? = null
    private var isServiceBound = false
    
    // 网络级别配置
    private enum class NetworkLevel(
        val displayName: String, 
        val downloadSpeed: Float, 
        val uploadSpeed: Float, 
        val latency: Int,
        val description: String
    ) {
        NORMAL("正常网络", 100f, 50f, 20, "100Mbps下载 / 50Mbps上传 / 20ms延迟"),
        FAST_4G("快速4G", 50f, 25f, 50, "50Mbps下载 / 25Mbps上传 / 50ms延迟"),
        NORMAL_4G("标准4G", 20f, 10f, 100, "20Mbps下载 / 10Mbps上传 / 100ms延迟"),
        SLOW_4G("慢速4G", 5f, 2f, 200, "5Mbps下载 / 2Mbps上传 / 200ms延迟"),
        FAST_3G("快速3G", 2f, 1f, 300, "2Mbps下载 / 1Mbps上传 / 300ms延迟"),
        NORMAL_3G("标准3G", 1f, 0.5f, 500, "1Mbps下载 / 0.5Mbps上传 / 500ms延迟"),
        SLOW_3G("慢速3G", 0.5f, 0.2f, 800, "0.5Mbps下载 / 0.2Mbps上传 / 800ms延迟"),
        FAST_2G("快速2G", 0.2f, 0.1f, 1000, "0.2Mbps下载 / 0.1Mbps上传 / 1000ms延迟"),
        NORMAL_2G("标准2G", 0.1f, 0.05f, 1500, "0.1Mbps下载 / 0.05Mbps上传 / 1500ms延迟"),
        SLOW_2G("慢速2G", 0.05f, 0.02f, 2000, "0.05Mbps下载 / 0.02Mbps上传 / 2000ms延迟"),
        GPRS("GPRS网络", 0.02f, 0.01f, 3000, "0.02Mbps下载 / 0.01Mbps上传 / 3000ms延迟"),
        VERY_SLOW("极慢网络", 0.01f, 0.005f, 5000, "0.01Mbps下载 / 0.005Mbps上传 / 5000ms延迟"),
        OFFLINE("离线模式", 0f, 0f, 9999, "无网络连接")
    }
    
    private var currentNetworkLevel = NetworkLevel.NORMAL

    // 服务连接
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NetworkProxyService.LocalBinder
            networkProxyService = binder.getService()
            isServiceBound = true
            Log.d("ToolsActivity", "Network proxy service connected")
            updateProxyStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            networkProxyService = null
            isServiceBound = false
            Log.d("ToolsActivity", "Network proxy service disconnected")
            updateProxyStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tools)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.tools_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        initViews()
        setupListeners()
        requestNotificationPermission()
        bindNetworkProxyService()
        updateNetworkStatus()
    }
    
    private fun initViews() {
        networkLevelGroup = findViewById(R.id.network_level_group)
        applyNetworkButton = findViewById(R.id.apply_network_button)
        downloadSpeedText = findViewById(R.id.download_speed_text)
        uploadSpeedText = findViewById(R.id.upload_speed_text)
        pingText = findViewById(R.id.ping_text)
        startSpeedTestButton = findViewById(R.id.start_speed_test_button)
        stopSpeedTestButton = findViewById(R.id.stop_speed_test_button)
        networkStatusText = findViewById(R.id.network_status_text)
        proxyStatusText = findViewById(R.id.proxy_status_text)
        setupProxyButton = findViewById(R.id.setup_proxy_button)
        networkDescriptionText = findViewById(R.id.network_description_text)
    }
    
    private fun setupListeners() {
        applyNetworkButton.setOnClickListener {
            applyNetworkSettings()
        }
        
        startSpeedTestButton.setOnClickListener {
            startSpeedTest()
        }
        
        stopSpeedTestButton.setOnClickListener {
            stopSpeedTest()
        }
        
        setupProxyButton.setOnClickListener {
            showProxySetupDialog()
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }
    
    private fun bindNetworkProxyService() {
        val intent = Intent(this, NetworkProxyService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(intent) // 启动前台服务
    }
    
    private fun unbindNetworkProxyService() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
    
    private fun showProxySetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("代理设置")
            .setMessage("""
                要使用网络限速功能，需要设置系统代理：
                
                1. 点击"打开设置"进入WiFi设置
                2. 长按当前连接的WiFi网络
                3. 选择"修改网络"
                4. 勾选"显示高级选项"
                5. 代理设置选择"手动"
                6. 代理服务器主机名：127.0.0.1
                7. 代理服务器端口：8080
                8. 保存设置
                
                设置完成后，所有网络流量将通过代理服务器，实现真正的网络限速。
                
                注意：设置代理后，请先点击"应用网络设置"启动代理服务器，然后再测试网络连接。
                
                如果设置代理后无法联网，请点击"快速修复"。
            """.trimIndent())
            .setPositiveButton("打开设置") { _, _ ->
                openWifiSettings()
            }
            .setNeutralButton("快速修复") { _, _ ->
                quickFix()
            }
            .setNegativeButton("诊断网络") { _, _ ->
                diagnoseNetwork()
            }
            .show()
    }
    
    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开WiFi设置", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testProxyConnection() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("ToolsActivity", "Testing proxy connection")
                
                // 首先检查代理服务是否正在运行
                if (!isServiceBound || networkProxyService == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ToolsActivity, "代理服务未连接，请先启动应用", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                if (!networkProxyService!!.isProxyRunning()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ToolsActivity, "代理服务器未运行，请先点击'应用网络设置'", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                // 尝试连接默认端口
                val defaultPort = 8080
                try {
                    val socket = Socket("127.0.0.1", defaultPort)
                    socket.close()
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ToolsActivity, "代理服务器连接正常 (端口: $defaultPort)", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } catch (e: Exception) {
                    Log.d("ToolsActivity", "Default port $defaultPort failed, trying alternative ports")
                }
                
                // 尝试其他端口
                val alternativePorts = listOf(8888, 8889, 8890, 8891)
                for (port in alternativePorts) {
                    try {
                        val socket = Socket("127.0.0.1", port)
                        socket.close()
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ToolsActivity, "代理服务器连接正常 (端口: $port)", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    } catch (e: Exception) {
                        Log.d("ToolsActivity", "Port $port failed: ${e.message}")
                        continue
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ToolsActivity, "代理服务器连接失败，请检查服务是否正常启动", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e("ToolsActivity", "Proxy connection test failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ToolsActivity, "代理服务器连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun diagnoseNetwork() {
        CoroutineScope(Dispatchers.IO).launch {
            val results = mutableListOf<String>()
            
            try {
                // 1. 检查代理服务状态
                results.add("=== 代理服务诊断 ===")
                if (!isServiceBound) {
                    results.add("❌ 代理服务未绑定")
                } else {
                    results.add("✅ 代理服务已绑定")
                }
                
                if (networkProxyService?.isProxyRunning() == true) {
                    val port = networkProxyService?.getCurrentProxyPort()
                    results.add("✅ 代理服务器运行中 (端口: $port)")
                } else {
                    results.add("❌ 代理服务器未运行")
                }
                
                // 2. 测试本地代理连接
                results.add("\n=== 本地代理连接测试 ===")
                val proxyPort = networkProxyService?.getCurrentProxyPort() ?: 8080
                try {
                    val socket = Socket("127.0.0.1", proxyPort)
                    socket.close()
                    results.add("✅ 本地代理连接正常 (端口: $proxyPort)")
                } catch (e: Exception) {
                    results.add("❌ 本地代理连接失败: ${e.message}")
                }
                
                // 3. 测试直接网络连接
                results.add("\n=== 直接网络连接测试 ===")
                try {
                    val connection = URL("https://www.baidu.com").openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    val responseCode = connection.responseCode
                    results.add("✅ 直接网络连接正常 (响应码: $responseCode)")
                } catch (e: Exception) {
                    results.add("❌ 直接网络连接失败: ${e.message}")
                }
                
                // 4. 测试通过代理的网络连接
                results.add("\n=== 代理网络连接测试 ===")
                try {
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                    val connection = URL("http://httpbin.org/get").openConnection(proxy) as HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    val responseCode = connection.responseCode
                    results.add("✅ 代理网络连接正常 (响应码: $responseCode)")
                } catch (e: Exception) {
                    results.add("❌ 代理网络连接失败: ${e.message}")
                    
                    // 尝试简单的HTTP请求
                    try {
                        results.add("\n=== 尝试HTTP请求测试 ===")
                        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                        val connection = URL("http://example.com").openConnection(proxy) as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        val responseCode = connection.responseCode
                        results.add("✅ HTTP代理连接正常 (响应码: $responseCode)")
                    } catch (e2: Exception) {
                        results.add("❌ HTTP代理连接也失败: ${e2.message}")
                        
                        // 尝试最简单的测试
                        try {
                            results.add("\n=== 尝试简单连接测试 ===")
                            val socket = Socket()
                            socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 5000)
                            
                            // 发送简单的HTTP请求
                            val output = socket.getOutputStream()
                            val input = socket.getInputStream()
                            
                            val request = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
                            output.write(request.toByteArray())
                            output.flush()
                            
                            val response = input.readBytes()
                            results.add("✅ 简单代理测试成功 (响应长度: ${response.size})")
                            
                            socket.close()
                        } catch (e3: Exception) {
                            results.add("❌ 简单代理测试也失败: ${e3.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                results.add("❌ 诊断过程出错: ${e.message}")
            }
            
            // 显示诊断结果
            withContext(Dispatchers.Main) {
                val dialog = AlertDialog.Builder(this@ToolsActivity)
                    .setTitle("网络诊断结果")
                    .setMessage(results.joinToString("\n"))
                    .setPositiveButton("确定", null)
                    .setNegativeButton("复制结果") { _, _ ->
                        copyToClipboard(results.joinToString("\n"))
                    }
                    .create()
                dialog.show()
            }
        }
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("网络诊断结果", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "诊断结果已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
    
    private fun applyNetworkSettings() {
        val selectedId = networkLevelGroup.checkedRadioButtonId
        currentNetworkLevel = when (selectedId) {
            R.id.normal_network -> NetworkLevel.NORMAL
            R.id.fast_4g_network -> NetworkLevel.FAST_4G
            R.id.normal_4g_network -> NetworkLevel.NORMAL_4G
            R.id.slow_4g_network -> NetworkLevel.SLOW_4G
            R.id.fast_3g_network -> NetworkLevel.FAST_3G
            R.id.normal_3g_network -> NetworkLevel.NORMAL_3G
            R.id.slow_3g_network -> NetworkLevel.SLOW_3G
            R.id.fast_2g_network -> NetworkLevel.FAST_2G
            R.id.normal_2g_network -> NetworkLevel.NORMAL_2G
            R.id.slow_2g_network -> NetworkLevel.SLOW_2G
            R.id.gprs_network -> NetworkLevel.GPRS
            R.id.very_slow_network -> NetworkLevel.VERY_SLOW
            R.id.offline_network -> NetworkLevel.OFFLINE
            else -> NetworkLevel.NORMAL
        }
        
        // 应用网络限速设置
        if (isServiceBound && networkProxyService != null) {
            val throttleConfig = when (currentNetworkLevel) {
                NetworkLevel.NORMAL -> NetworkProxyService.NORMAL_CONFIG
                NetworkLevel.FAST_4G -> NetworkProxyService.FAST_4G_CONFIG
                NetworkLevel.NORMAL_4G -> NetworkProxyService.NORMAL_4G_CONFIG
                NetworkLevel.SLOW_4G -> NetworkProxyService.SLOW_4G_CONFIG
                NetworkLevel.FAST_3G -> NetworkProxyService.FAST_3G_CONFIG
                NetworkLevel.NORMAL_3G -> NetworkProxyService.NORMAL_3G_CONFIG
                NetworkLevel.SLOW_3G -> NetworkProxyService.SLOW_3G_CONFIG
                NetworkLevel.FAST_2G -> NetworkProxyService.FAST_2G_CONFIG
                NetworkLevel.NORMAL_2G -> NetworkProxyService.NORMAL_2G_CONFIG
                NetworkLevel.SLOW_2G -> NetworkProxyService.SLOW_2G_CONFIG
                NetworkLevel.GPRS -> NetworkProxyService.GPRS_CONFIG
                NetworkLevel.VERY_SLOW -> NetworkProxyService.VERY_SLOW_CONFIG
                NetworkLevel.OFFLINE -> NetworkProxyService.OFFLINE_CONFIG
            }
            
            Log.d("ToolsActivity", "Setting throttle config: $throttleConfig")
            networkProxyService!!.setThrottleConfig(throttleConfig)
            
            // 启动代理服务器
            if (!networkProxyService!!.isProxyRunning()) {
                Log.d("ToolsActivity", "Starting proxy server")
                networkProxyService!!.startProxy()
                
                // 等待一下让代理服务器启动
                handler.postDelayed({
                    updateProxyStatus()
                    if (networkProxyService!!.isProxyRunning()) {
                        Toast.makeText(this, "代理服务器已启动，网络限速已生效", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "代理服务器启动失败，请检查端口是否被占用", Toast.LENGTH_LONG).show()
                    }
                }, 1000)
            } else {
                Log.d("ToolsActivity", "Proxy server is already running")
                Toast.makeText(this, "已应用${currentNetworkLevel.displayName}设置", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e("ToolsActivity", "Network proxy service not bound")
            Toast.makeText(this, "网络代理服务未连接", Toast.LENGTH_SHORT).show()
        }
        
        updateNetworkStatus()
        updateProxyStatus()
    }
    
    private fun updateNetworkStatus() {
        val statusText = "网络状态: ${currentNetworkLevel.displayName}"
        val statusColor = when (currentNetworkLevel) {
            NetworkLevel.NORMAL -> "#4CAF50" // 绿色
            NetworkLevel.FAST_4G -> "#4CAF50" // 绿色
            NetworkLevel.NORMAL_4G -> "#8BC34A" // 浅绿色
            NetworkLevel.SLOW_4G -> "#CDDC39" // 黄绿色
            NetworkLevel.FAST_3G -> "#FFEB3B" // 黄色
            NetworkLevel.NORMAL_3G -> "#FF9800" // 橙色
            NetworkLevel.SLOW_3G -> "#FF5722" // 深橙色
            NetworkLevel.FAST_2G -> "#F44336" // 红色
            NetworkLevel.NORMAL_2G -> "#E91E63" // 粉红色
            NetworkLevel.SLOW_2G -> "#9C27B0" // 紫色
            NetworkLevel.GPRS -> "#673AB7" // 深紫色
            NetworkLevel.VERY_SLOW -> "#3F51B5" // 靛蓝色
            NetworkLevel.OFFLINE -> "#9E9E9E" // 灰色
        }
        
        networkStatusText.text = statusText
        networkStatusText.setTextColor(android.graphics.Color.parseColor(statusColor))
        
        // 更新网速显示
        updateSpeedDisplay()
    }
    
    private fun updateProxyStatus() {
        val isRunning = isServiceBound && networkProxyService?.isProxyRunning() == true
        val actualPort = if (isRunning) networkProxyService?.getCurrentProxyPort() else null
        val statusText = if (isRunning) {
            "代理状态: 运行中 (端口: $actualPort)"
        } else {
            "代理状态: 未运行"
        }
        val statusColor = if (isRunning) "#4CAF50" else "#F44336"
        
        proxyStatusText.text = statusText
        proxyStatusText.setTextColor(android.graphics.Color.parseColor(statusColor))
    }
    
    private fun updateSpeedDisplay() {
        downloadSpeedText.text = "下载速度: ${currentNetworkLevel.downloadSpeed} Mbps"
        uploadSpeedText.text = "上传速度: ${currentNetworkLevel.uploadSpeed} Mbps"
        pingText.text = "延迟: ${currentNetworkLevel.latency} ms"
        
        // 添加详细描述
        val descriptionText = currentNetworkLevel.description
        networkDescriptionText.text = descriptionText
    }
    
    private fun startSpeedTest() {
        if (isSpeedTestRunning) return
        
        isSpeedTestRunning = true
        startSpeedTestButton.isEnabled = false
        stopSpeedTestButton.isEnabled = true
        
        speedTestJob = CoroutineScope(Dispatchers.IO).launch {
            while (isSpeedTestRunning) {
                performRealSpeedTest()
                delay(2000) // 每2秒更新一次
            }
        }
    }
    
    private fun stopSpeedTest() {
        isSpeedTestRunning = false
        speedTestJob?.cancel()
        startSpeedTestButton.isEnabled = true
        stopSpeedTestButton.isEnabled = false
    }
    
    private suspend fun performRealSpeedTest() {
        try {
            if (!isServiceBound || networkProxyService == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ToolsActivity, "网络代理服务未连接", Toast.LENGTH_SHORT).show()
                }
                return
            }
            
            val testUrl = "https://httpbin.org/bytes/1024" // 1KB测试文件
            val startTime = System.currentTimeMillis()
            
            // 执行下载测试
            val connection = URL(testUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val inputStream = connection.inputStream
            val buffer = ByteArray(1024)
            var totalBytesRead = 0
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalBytesRead += bytesRead
            }
            
            inputStream.close()
            val downloadTime = System.currentTimeMillis() - startTime
            
            // 计算实际下载速度
            val downloadSpeedKbps = if (downloadTime > 0) {
                (totalBytesRead * 8.0 / 1024.0 / downloadTime * 1000).toFloat()
            } else {
                0f
            }
            
            // 获取当前配置
            val currentConfig = networkProxyService!!.getCurrentConfig()
            val latency = currentConfig.latencyMs
            
            withContext(Dispatchers.Main) {
                downloadSpeedText.text = "下载速度: ${(downloadSpeedKbps / 1000).toFloat().roundToInt() / 1000f} Mbps"
                uploadSpeedText.text = "上传速度: ${(currentConfig.uploadSpeedKbps / 1000).toFloat().roundToInt() / 1000f} Mbps"
                pingText.text = "延迟: ${latency} ms"
            }
            
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ToolsActivity, "网速测试出错: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun quickFix() {
        AlertDialog.Builder(this)
            .setTitle("快速修复")
            .setMessage("""
                如果设置代理后无法联网，请按以下步骤操作：
                
                1. 先关闭代理服务器（点击"停止网速测试"）
                2. 进入WiFi设置，将代理设置为"无"
                3. 测试网络是否恢复正常
                4. 如果网络正常，再重新设置代理
                
                或者：
                
                1. 重启应用
                2. 重新设置代理
                3. 确保代理服务器已启动
                
                点击"停止代理"可以立即停止代理服务器。
            """.trimIndent())
            .setPositiveButton("停止代理") { _, _ ->
                stopProxyServer()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun stopProxyServer() {
        if (isServiceBound && networkProxyService != null) {
            networkProxyService!!.stopProxy()
            updateProxyStatus()
            Toast.makeText(this, "代理服务器已停止", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "代理服务器未运行", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopSpeedTest()
        unbindNetworkProxyService()
        executor.shutdown()
    }
} 