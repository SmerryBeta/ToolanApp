# 如何构建发布版本APK

## 方法一：使用Android Studio图形界面

1. 打开Android Studio并加载你的项目
2. 在菜单栏中选择 `Build` -> `Generate Signed Bundle / APK`
3. 在弹出的对话框中选择 `APK` 并点击 `Next`
4. 如果已有签名密钥，请选择它；如果没有，点击 `Create new...` 创建新的密钥
5. 填写以下信息：
   - Key store path: 选择一个安全的位置保存你的密钥库文件
   - Password: 设置密钥库密码
   - Alias: 设置密钥别名
   - Password: 设置密钥密码
   - 复选"记住密码"（可选）
6. 选择 `release` 构建类型，然后点击 `Finish`
7. Android Studio将开始构建发布版本APK，完成后会显示完成通知

## 方法二：使用命令行

在项目根目录下打开终端或命令提示符，运行以下命令：

```bash
./gradlew assembleRelease
```

Windows系统使用：
```cmd
gradlew.bat assembleRelease
```

构建完成后，APK文件将位于 `app/build/outputs/apk/release/` 目录下。

注意：当前生成的是 `app-release-unsigned.apk` 或 `app-release.apk`，这是未签名或已签名的APK。

## 关于签名配置

如果要手动配置签名，需要在 `app/build.gradle.kts` 中添加如下配置：

```kotlin
android {
    ...
    
    signingConfigs {
        create("release") {
            storeFile = file("your-upload-keystore.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: project.findProperty("signingStorePassword") as String?
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: project.findProperty("signingKeyAlias") as String?
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: project.findProperty("signingKeyPassword") as String?
        }
    }

    buildTypes {
        release {
            ...
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

## 如何获取正式签名的APK

由于当前项目还没有配置签名密钥，生成的APK是未签名的（app-release-unsigned.apk）。为了发布到应用商店，你需要：

1. 创建签名密钥：
   - 在Android Studio中：`Build` -> `Generate Signed Bundle / APK` -> `Create new...`
   - 使用命令行：
   ```bash
   keytool -genkey -v -keystore my-upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
   ```

2. 将生成的密钥库文件（.jks）复制到项目 `app/` 目录下

3. 修改 `app/build.gradle.kts` 文件，按照上面的签名配置示例进行配置

4. 再次运行 `./gradlew assembleRelease` 命令，将生成已签名的APK

## 注意事项

1. **密钥安全**：请妥善保管你的密钥库文件和密码，一旦丢失将无法更新已发布的应用
2. **混淆设置**：在发布版本中启用代码混淆可以提高安全性，可在build.gradle中设置 `isMinifyEnabled = true`
3. **版本号**：确保每次发布前更新 `versionCode` 和 `versionName`
4. **测试**：发布前务必在不同设备上充分测试release版本

## 优化发布版本

为了减小APK大小和提高性能，建议在 `build.gradle` 中启用以下选项：

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true // 启用代码压缩和混淆
        isShrinkResources = true // 启用资源压缩
        proguardFiles getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
    }
}
```