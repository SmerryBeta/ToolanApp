# MyApplication

一个基于Android的多功能应用程序，集成了底部导航、通知管理、音效播放等核心功能。

## 🚀 项目特性

- **底部导航架构** - 使用Navigation Component实现流畅的页面切换
- **通知管理系统** - 支持自定义通知渠道和锁屏显示
- **音效播放功能** - 集成SoundManager管理多种音频效果
- **HTTP服务端** - 内置轻量级HTTP服务器用于通知触发
- **现代化UI设计** - 采用Material Design 3组件

## 🛠 技术栈

### 核心框架
- **Android SDK** - 最新Android开发平台
- **Kotlin** - 主要开发语言
- **Android Gradle Plugin** - 构建工具
- **Gradle Kotlin DSL** - 构建脚本配置

### 主要组件
- **Navigation Component** - 页面导航管理
- **Data Binding** - 视图绑定
- **Material Design 3** - UI组件库
- **AndroidX** - Jetpack组件库

### 网络与服务
- **内置HTTP服务器** - 用于本地通知触发
- **WebSocket支持** - 实时通信功能

## 📁 项目结构

```
app/
├── src/main/java/com/example/myapplication/
│   ├── services/                 # 后台服务
│   │   ├── NotifyHttpServer.java # HTTP通知服务
│   │   └── ...                   # 其他服务
│   ├── utils/                    # 工具类
│   │   └── ...                   # 工具方法
│   ├── MainActivity.java         # 主Activity
│   ├── SoundManager.java         # 音效管理器
│   ├── SPUtils.java              # SharedPreferences工具
│   ├── NotifyNotificationFactory.java # 通知工厂类
│   └── ...                       # Fragment组件
├── src/main/res/                 # 资源文件
│   ├── layout/                   # 布局文件
│   ├── drawable/                 # 图标资源
│   ├── values/                   # 字符串、颜色等资源
│   └── navigation/               # 导航图
└── build.gradle.kts             # 模块构建配置
```

## 🔧 环境要求

- **Android Studio** Arctic Fox或更高版本
- **JDK 11** 或更高版本
- **Android SDK** API 21+
- **Gradle** 7.4+

## 🚀 快速开始

### 1. 克隆项目
```bash
git clone https://github.com/你的用户名/MyApplication.git
cd MyApplication
```

### 2. 打开项目
使用Android Studio打开项目根目录

### 3. 同步依赖
```bash
./gradlew build
```

### 4. 运行应用
连接Android设备或启动模拟器，然后运行：
```bash
./gradlew installDebug
```

## 📱 主要功能模块

### 1. 底部导航
- 集成BottomNavigationView
- 支持3个主要功能页面切换
- 流畅的页面过渡动画

### 2. 通知系统
- 自定义通知渠道管理
- 支持锁屏显示
- 可配置震动和音效
- HTTP接口触发通知

### 3. 音效管理
- 集成SoundManager类
- 支持多种音频格式
- 可配置音量和播放模式

### 4. 设置功能
- 应用配置管理
- 用户偏好设置
- 系统参数调整

## 🛠 开发配置

### gradle.properties 配置
```properties
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.configuration-cache=true
```

### 主要依赖
```kotlin
implementation 'androidx.navigation:navigation-fragment-ktx:2.7.5'
implementation 'androidx.navigation:navigation-ui-ktx:2.7.5'
implementation 'com.google.android.material:material:1.10.0'
```

## 📊 项目状态

- [x] 基础架构搭建
- [x] 底部导航实现
- [x] 通知系统集成
- [x] 音效播放功能
- [x] HTTP服务端
- [ ] 单元测试覆盖
- [ ] 性能优化

## 🤝 贡献指南

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 📞 联系方式

项目维护者: [你的名字]  
邮箱: [你的邮箱]  
GitHub: [你的GitHub链接]

---

**注意**: 请确保在使用前配置好Android开发环境，并根据需要调整项目配置。