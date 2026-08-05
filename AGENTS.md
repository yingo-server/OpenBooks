# AGENTS.md

## 项目概况

仓库根目录是双版本布局,本文件对应 **old/(经典版)**:

```
OpenBooks/
├── .github/workflows/build.yml   ← CI 构建 old/(经典版);GitHub Actions 只读根目录,无法移入子目录
├── old/                          ← 经典版(本文件):Android 4.4–5.1,工具链保持老旧原样
└── new/                          ← 现代版(5.1–12L,待从修复版 old/ 复制创建)
```

面向 Android 4.4 (KitKat) 的极简小说阅读器(OpenBook,包名 `com.openbook.studio`)。全部代码集中在一个文件 `app/src/main/java/com/openbook/studio/MainActivity.java`(约 1400 行),无 XML 布局、无资源文件(仅 app_name)、无测试、无 lint 配置。代码注释与日志均为中文,保持一致。两版存储格式一致(`/sdcard/openbook/`),数据互通。

## 构建环境(最容易踩坑)

工具链非常老旧,现代 Gradle/AGP/Android Studio 无法构建:

- **Gradle 4.10.1 + AGP 3.2.1 + JDK 8**(AGP 3.2 不支持 JDK 9+;CI 用 JDK 21 只负责装 SDK,构建用 JDK 8)
- **compileSdk / minSdk / targetSdk 均为 19**,需安装 `platforms;android-19` 与 `build-tools;26.0.2`
- 仓库**没有 `gradlew`**,只有 `gradle-wrapper.properties`。构建命令:`gradle clean assembleDebug`(系统级 Gradle 4.10.1,参考 `.github/workflows/build.yml` 的完整步骤)
- 产物:`app/build/outputs/apk/debug/app-debug.apk`;没有自动化测试,验证方式就是构建出 debug APK

## 代码约束(易错点)

- **未启用 AndroidX**(`android.useAndroidX=false`):禁止添加 `androidx.*` 依赖
- **Java 7 兼容**(source/target 1.7):禁止 lambda、方法引用、try-with-resources 之外的 Java 8+ 语法
- **OkHttp 必须保持 3.x**(现为 3.12.13):OkHttp 4.x 起 minSdk 21,无法用于 Android 4.4
- `jcenter()` 已停服,新增依赖必须发布在 google()/mavenCentral() 且兼容 API 19
- `buildTls12Client()` 中「信任所有证书 + 忽略主机名校验」是**故意的**(Android 4.4 无 TLS 1.2,靠 Conscrypt),不要"修复"

## 架构要点

- UI 是 SurfaceView 手绘 Canvas,内部逻辑固定 **240x240**,经**分辨率适配**等比缩放到物理屏幕:最大内接正方形 `scale=min(w,h)/240` 居中(上下或左右黑边),`surfaceChanged` 计算 `viewScale/viewOffsetX/viewOffsetY`,`drawUI` 用 canvas.save/translate/scale 绘制,**触摸坐标必须经 `toLogicX/toLogicY` 逆变换**后再走逻辑像素逻辑(48/18/120 等全为逻辑值);渲染循环约 20fps(50ms sleep)
- 状态机:`STATE_BOOK_LIST`=0 / `STATE_READING`=1 / `STATE_SELECT_CHAPTER`=2,由 `drawUI()` 分发
- 底部是**双进度条**(`drawProgressBars`,各 3px 高、贴底,不遮挡正文):青色=本节进度(阅读态=当前页/总页数),白色=整书总进度(阅读态=整书位置,分母 `chapterList.size()`;书列表/章节选择态=列表滚动位置);`statusMessage` 字段仅日志用,不再绘制
- 触摸处理依赖硬编码像素值:书列表项高 48px、章节项高 18px(底部 18px 提示区不响应点击)、半屏分割 120px、长按阈值 1500ms、翻页左右半屏 120px——改动布局时需同步修改 `onTouchEvent`
- 列表滚动:**ACTION_MOVE 实时跟手**,ACTION_UP 保留惯性(速度先减半,阈值 2.0px/帧起滑,渲染循环 `updateInertia()` 每帧衰减 0.80,`applyScroll` 负责钳位,到边界即停);改滚动手感时同步 `FLING_THRESHOLD/FLING_DECAY/FLING_MIN`
- 阅读排版:11x11 字符网格(`COLS=11, ROWS=11`)、字号 20px
- 数据全部存外部存储 `/sdcard/openbook/`:
  - `config/user/config.ob`:远程配置缓存;配置解析格式为每行 `key@value`,以 `!!!!!` 行结束,value 尾部 `!` 为转义(见 `ConfigManager.parseConfig`)
  - `config/user/progress.ob`:阅读进度,格式 `bookId@chapter,page`
  - `books/<bookId>/content.ob`:章节目录缓存(行格式 `itemId@title`);`books/<bookId>/data%04d.ob`:章节缓存,最多保留 3 章
  - `logs/<yyyy-MM-dd-HH-mm-ss>/logs.ob`:日志,自动清理非当天的目录
- 网络:`ConfigManager` 从 gitee.com raw 拉取配置;`ApiClient` 请求 `https://v3.rain.ink/fanqie/`(`type=3` 目录 / `type=4` 章节内容),多 API key 轮换(`&apikey=`),`httpGet` 失败会换 key 重试

## 交互(调试/改逻辑时对照)

- 阅读:点击左/右半屏 = 上一页/下一页;**上半屏长按** = 进入章节选择,**下半屏长按** = 退出到书列表
- 章节选择:点击切换章节,长按返回书列表
- 切换书籍时从 `progress.ob` 恢复上次章节页码

## CI / 发布

- `.github/workflows/build.yml`:push 到 main/master 或打 `v*` tag 触发;打 tag 会自动创建 GitHub Release 并附 debug APK
- 新 API 若需系统权限,记得同步 `AndroidManifest.xml`(目前有 INTERNET、外置存储读写、WAKE_LOCK)
