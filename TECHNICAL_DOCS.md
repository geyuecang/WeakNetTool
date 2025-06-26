# MyNetTools 技术文档

## 📋 目录
1. [架构概述](#架构概述)
2. [核心组件](#核心组件)
3. [API文档](#api文档)
4. [数据流](#数据流)
5. [配置说明](#配置说明)
6. [开发指南](#开发指南)
7. [部署说明](#部署说明)

## 🏗️ 架构概述

MyNetTools采用模块化架构设计，主要包含以下核心模块：

```
MyNetTools/
├── UI Layer (Activities)
│   ├── MainActivity          # 主界面
│   └── ToolsActivity         # 网络工具界面
├── Service Layer
│   ├── NetworkProxyService   # 代理服务器
│   └── NetworkThrottleService # 限速服务
├── Data Layer
│   ├── ThrottleConfig        # 限速配置
│   └── NetworkLevel          # 网络等级枚举
└── Utils
    ├── NetworkDiagnostics    # 网络诊断
    └── SpeedTest             # 网速测试
```

### 架构特点

- **分层设计**：UI、Service、Data三层分离
- **模块化**：每个功能模块独立，便于维护
- **可扩展**：支持添加新的网络等级和功能
- **高性能**：多线程处理，高效的内存管理

## 🔧 核心组件

### 1. NetworkProxyService

本地HTTP/HTTPS代理服务器，负责网络流量的拦截和限速。

#### 主要功能
- HTTP/HTTPS代理转发
- 实时网络限速
- 延迟控制
- 端口管理
- 连接诊断

#### 核心方法

```kotlin
class NetworkProxyService : Service() {
    // 启动代理服务器
    fun startProxy()
    
    // 停止代理服务器
    fun stopProxy()
    
    // 设置限速配置
    fun setThrottleConfig(config: ThrottleConfig)
    
    // 获取当前配置
    fun getCurrentConfig(): ThrottleConfig
    
    // 检查代理状态
    fun isProxyRunning(): Boolean
    
    // 获取代理端口
    fun getCurrentProxyPort(): Int
}
```

#### 配置参数

```kotlin
data class ThrottleConfig(
    val downloadSpeedKbps: Int,  // 下载速度限制 (Kbps)
    val uploadSpeedKbps: Int,    // 上传速度限制 (Kbps)
    val latencyMs: Int,          // 延迟 (毫秒)
    val isOffline: Boolean = false // 离线模式标志
)
```

### 2. ToolsActivity

用户界面控制器，处理用户交互和网络配置。

#### 主要功能
- 网络等级选择
- 代理设置指导
- 网速监控
- 网络诊断
- 状态显示

#### 核心方法

```kotlin
class ToolsActivity : AppCompatActivity() {
    // 应用网络设置
    private fun applyNetworkSettings()
    
    // 启动网速测试
    private fun startSpeedTest()
    
    // 停止网速测试
    private fun stopSpeedTest()
    
    // 网络诊断
    private fun diagnoseNetwork()
    
    // 更新网络状态
    private fun updateNetworkStatus()
}
```

### 3. NetworkLevel 枚举

定义所有可用的网络等级和配置。

```kotlin
enum class NetworkLevel(
    val displayName: String,
    val downloadSpeed: Float,
    val uploadSpeed: Float,
    val latency: Int,
    val description: String
) {
    NORMAL("正常网络", 100f, 50f, 20, "100Mbps下载 / 50Mbps上传 / 20ms延迟"),
    FAST_4G("快速4G", 50f, 25f, 50, "50Mbps下载 / 25Mbps上传 / 50ms延迟"),
    // ... 其他网络等级
}
```

## 📡 API文档

### NetworkProxyService API

#### 启动代理服务
```kotlin
fun startProxy()
```
**功能**：启动本地代理服务器
**参数**：无
**返回值**：无
**异常**：可能抛出BindException（端口被占用）

#### 停止代理服务
```kotlin
fun stopProxy()
```
**功能**：停止代理服务器并释放资源
**参数**：无
**返回值**：无

#### 设置限速配置
```kotlin
fun setThrottleConfig(config: ThrottleConfig)
```
**功能**：设置网络限速参数
**参数**：
- `config`: ThrottleConfig对象，包含限速参数
**返回值**：无

#### 获取当前配置
```kotlin
fun getCurrentConfig(): ThrottleConfig
```
**功能**：获取当前的限速配置
**参数**：无
**返回值**：ThrottleConfig对象

#### 检查代理状态
```kotlin
fun isProxyRunning(): Boolean
```
**功能**：检查代理服务器是否正在运行
**参数**：无
**返回值**：true表示运行中，false表示未运行

### ToolsActivity API

#### 应用网络设置
```kotlin
private fun applyNetworkSettings()
```
**功能**：应用用户选择的网络等级设置
**参数**：无
**返回值**：无

#### 网络诊断
```kotlin
private fun diagnoseNetwork()
```
**功能**：执行网络连接诊断
**参数**：无
**返回值**：无
**说明**：异步执行，结果通过对话框显示

## 🔄 数据流

### 网络请求处理流程

```
客户端应用 → 系统代理 → NetworkProxyService → 目标服务器
                ↓
           限速和延迟控制
                ↓
           响应返回客户端
```

### 详细流程

1. **请求拦截**
   - 客户端应用发起网络请求
   - 系统代理将请求转发到NetworkProxyService
   - 代理服务解析HTTP/HTTPS请求

2. **限速处理**
   - 检查当前ThrottleConfig配置
   - 应用下载/上传速度限制
   - 添加网络延迟

3. **请求转发**
   - 建立到目标服务器的连接
   - 转发请求数据
   - 接收服务器响应

4. **响应返回**
   - 应用限速到响应数据
   - 返回给客户端应用

### 配置更新流程

```
用户选择网络等级 → ToolsActivity → NetworkProxyService → 更新ThrottleConfig
```

## ⚙️ 配置说明

### 网络等级配置

每个网络等级都有以下配置参数：

| 参数 | 类型 | 说明 | 示例 |
|------|------|------|------|
| displayName | String | 显示名称 | "快速4G" |
| downloadSpeed | Float | 下载速度(Mbps) | 50.0f |
| uploadSpeed | Float | 上传速度(Mbps) | 25.0f |
| latency | Int | 延迟(ms) | 50 |
| description | String | 详细描述 | "50Mbps下载 / 25Mbps上传 / 50ms延迟" |

### 代理服务器配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 默认端口 | 8080 | 代理服务器监听端口 |
| 备用端口 | 8888,8889,8890,8891 | 端口冲突时的备用端口 |
| 连接超时 | 30秒 | 网络连接超时时间 |
| 缓冲区大小 | 8192字节 | 数据传输缓冲区大小 |

### 限速算法

#### 下载限速
```kotlin
if (currentConfig.downloadSpeedKbps > 0) {
    val delayMs = (bytesRead * 8 * 1000L) / (currentConfig.downloadSpeedKbps * 1024)
    if (delayMs > 0) {
        Thread.sleep(delayMs)
    }
}
```

#### 上传限速
```kotlin
if (currentConfig.uploadSpeedKbps > 0) {
    val delayMs = (bytesRead * 8 * 1000L) / (currentConfig.uploadSpeedKbps * 1024)
    if (delayMs > 0) {
        Thread.sleep(delayMs)
    }
}
```

## 🛠️ 开发指南

### 环境要求

- Android Studio 4.0+
- Kotlin 1.5+
- Android SDK API 23+
- Gradle 7.0+

### 项目结构

```
app/src/main/
├── java/com/gc/nettools/
│   ├── MainActivity.kt
│   ├── ToolsActivity.kt
│   ├── NetworkProxyService.kt
│   └── NetworkThrottleService.kt
├── res/
│   ├── layout/
│   │   ├── activity_main.xml
│   │   └── activity_tools.xml
│   ├── values/
│   │   ├── strings.xml
│   │   ├── colors.xml
│   │   └── themes.xml
│   └── xml/
│       └── network_security_config.xml
└── AndroidManifest.xml
```

### 添加新的网络等级

1. **更新NetworkLevel枚举**
```kotlin
enum class NetworkLevel(
    val displayName: String,
    val downloadSpeed: Float,
    val uploadSpeed: Float,
    val latency: Int,
    val description: String
) {
    // 添加新的网络等级
    NEW_LEVEL("新等级", 10f, 5f, 100, "10Mbps下载 / 5Mbps上传 / 100ms延迟")
}
```

2. **更新NetworkProxyService配置**
```kotlin
val NEW_LEVEL_CONFIG = ThrottleConfig(10000, 5000, 100, false)
```

3. **更新ToolsActivity**
```kotlin
// 在applyNetworkSettings方法中添加
when (selectedId) {
    R.id.new_level_network -> NetworkLevel.NEW_LEVEL
    // ...
}

// 在throttleConfig映射中添加
NetworkLevel.NEW_LEVEL -> NetworkProxyService.NEW_LEVEL_CONFIG
```

4. **更新布局文件**
```xml
<RadioButton
    android:id="@+id/new_level_network"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="新等级 (10Mbps下载 / 5Mbps上传 / 100ms延迟)"
    android:padding="8dp" />
```

### 调试技巧

#### 日志查看
应用使用Android Logcat记录详细日志：
```bash
adb logcat | grep "NetworkProxyService\|ToolsActivity"
```

#### 网络诊断
使用内置诊断工具检查网络状态：
```kotlin
// 在ToolsActivity中调用
diagnoseNetwork()
```

#### 性能监控
监控代理服务的性能指标：
- 连接数
- 数据传输量
- 响应时间
- 错误率

## 🚀 部署说明

### 构建APK

1. **Debug版本**
```bash
./gradlew assembleDebug
```

2. **Release版本**
```bash
./gradlew assembleRelease
```

### 安装部署

1. **直接安装**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

2. **覆盖安装**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 权限配置

确保AndroidManifest.xml包含必要权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 网络安全配置

配置network_security_config.xml允许明文HTTP流量：

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">example.com</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system"/>
        </trust-anchors>
    </base-config>
</network-security-config>
```

---

**MyNetTools技术文档** - 为开发者提供完整的技术参考！ 