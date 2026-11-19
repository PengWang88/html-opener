# HTML 打开器

仓库：<https://github.com/PengWang88/html-opener>

一个最小化的 Android 应用，用来打开本地 Web 应用，并提供快捷链接：

1. 点击右上角「导入」，选择一个包含多个 Web 应用子文件夹的目录。应用只记录该目录的访问授权（引用导入），不复制任何文件。
2. 点击列表中的子文件夹，用手机上的浏览器打开对应的 Web 应用。系统弹窗会提供「仅一次 / 始终」。
3. 底部切换到「02 链接」，可以添加、编辑、删除常用网址，点击直接用浏览器打开。
4. 长按应用或链接条目，可以把该条目添加为桌面快捷方式。
5. Web 应用可以通过同源 `fetch` 读写自身目录下的 `state.json` 和 `articles/*.json`（v1.12+，导入时需要授予写权限）。

## 安装

仓库内附带的已签名 APK 是 v1.12（versionCode 13），包含写权限和 JSON 写接口。
调试版可通过 `./gradlew assembleDebug` 本地构建。

发布版使用独立签名，与调试版签名不同：

**从 v1.3–v1.11 发布版升级**（同一签名，保留已导入目录和快捷链接）：

```bash
adb install -r HTMLOpener-release.apk
```

**从调试版或其他签名版本切换**（必须先卸载，已导入目录会清除）：

```bash
adb uninstall com.example.htmlopener
adb install HTMLOpener-release.apk
```

也可以把 APK 传到手机上点击安装；签名不一致时系统会提示先卸载旧版本。

从 v1.11 升级到 v1.12 后，原有目录授权仍然只有读权限：应用会显示「只读」，保存接口返回 `403`。请点「导入 +」重新选择同一文件夹，并在系统提示中允许修改；`state.json` 和 `articles/` 中的数据不会丢失。

- 包名：`com.example.htmlopener`
- 最低系统：Android 6.0（API 23）
- 目标系统：Android 14（API 34）
- 源码版本：1.12（versionCode 13）
- 仓库内 APK：v1.12（versionCode 13），约 122 KB
- v1.12 APK SHA-256：`f73a4db69ea17ecb3f66f661594c9cba1d3ef246e74552c9c5c0e2e6ae023dba`

## 界面

- 瑞士国际主义风格：纯白、黑、红三色，大字号标题，编号列表，细线分隔，无圆角卡片。
- 底部双标签导航：`01 应用` / `02 链接`，红色顶部指示条标识当前页。
- 应用页显示序号（01、02…）、应用名和入口文件；链接页显示名称和网址；空状态使用大号“00”和引导文字。
- 应用图标为红底白色 `<>` 标记，包含自适应图标、圆形图标和旧版 PNG。

![应用图标](app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

## 目录结构约定

选择一个父目录，**每个一级子文件夹就是一个 Web 应用**：

```text
选择的父目录/
├── app-one/
│   ├── index.html      ← 入口，存在时优先打开
│   ├── style.css
│   └── js/app.js
├── app-two/
│   ├── index.html
│   └── assets/...
└── index.html          ← 可选：父目录自身也会显示为一个应用
```

规则：

- 子文件夹内有 `index.html` 或 `index.htm` 时，点击该子文件夹打开它。
- 没有 index 时，打开该子文件夹内按名称排序的第一个 `.html` / `.htm`。
- 只扫描一级子文件夹；子文件夹内部的 JS/CSS/图片等资源不受目录层级限制，均可加载。
- 如果所选父目录自身包含 `index.html`，父目录也会显示为一个应用。
- 如果某个子文件夹内没有 HTML，则不会出现在列表中。
- 应用可通过写接口维护自己的 `state.json` 和 `articles/*.json`；这些 JSON 数据文件和普通资源一样放在应用目录里，不会被单独列为应用。

## 快捷链接

- 底部导航切到「02 链接」。
- 点右上角「添加 +」，输入名称和网址；网址不写 `https://` 也可以，会自动补全。
- 点击条目直接用浏览器打开。
- 点条目右侧「编辑」可以修改名称/网址，也可以删除。
- 链接保存在应用本地 `SharedPreferences` 中，不会上传到网络。

## 桌面快捷方式

- 在「01 应用」或「02 链接」页长按任意条目，即可请求把该条目添加到桌面。
- 快捷方式使用自动生成的名称缩写图标，并按目标稳定分配深色背景，不会与软件自身图标混淆，也不需要联网获取 favicon。
- Android 8.0 及以上使用系统固定快捷方式，桌面会弹出确认提示；点击确认后即可从桌面直接打开对应的本地 Web 应用或链接。
- Android 7.1 及以下使用旧版桌面快捷方式广播，兼容性取决于手机桌面。
- 本地 Web 应用快捷方式在点击时会重新启动本地文件服务，因此即使应用进程已被回收也能正常打开；如果原目录授权已失效，需要回到应用重新导入目录。
- 如果桌面不支持固定快捷方式，应用会提示「当前桌面不支持添加固定快捷方式」。

## Web 应用保存数据（v1.12+）

页面可以把 JSON 数据写在应用自己的目录里，不需要额外后端、CORS 或浏览器 `localStorage`。写接口和页面使用同一个本地服务、同一个端口和随机 token，页面直接用相对路径访问即可。

```js
async function saveState(state) {
  const res = await fetch('./state.json', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(state)
  });
  if (!res.ok) throw new Error(await res.text());
}

async function loadState() {
  const res = await fetch('./state.json', { cache: 'no-store' });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}
```

文章也可以按文件保存：

```js
const url = './articles/' + encodeURIComponent(id) + '.json';

await fetch(url, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(article)
});

await fetch(url, { method: 'DELETE' });
```

### 支持的方法与路径

| 方法 | 路径 | 结果 |
| --- | --- | --- |
| `GET` / `HEAD` | 任意文件 | 原有静态文件读取 |
| `PUT` | `<应用目录>/state.json` | 不存在时创建，返回 `201`；已存在时覆盖，返回 `204` |
| `PUT` | `<应用目录>/articles/<name>.json` | 同上；首次写入会自动创建 `articles/` |
| `DELETE` | `<应用目录>/state.json` | 成功返回 `204`，文件不存在返回 `404` |
| `DELETE` | `<应用目录>/articles/<name>.json` | 成功返回 `204`；只能删除文件，目录返回 `403` |

- “应用目录”指当前页面所属的一级应用文件夹；如果父目录自身就是应用，则是父目录根。
- 页面在应用目录根部时，`fetch('./state.json')` 就是 `<应用目录>/state.json`。页面在子目录时，请使用正确的相对路径（例如 `../state.json`）；写入目标必须仍在当前应用目录内。
- 同源页面发起的请求会被限定在当前应用目录：服务端用同源 `Referer` 识别页面所属应用，从页面内 `fetch` 会自动携带 Referer。没有 Referer 的命令行请求只按路径形状做兜底校验，不保证跨应用隔离。
- 页面不要设置 `no-referrer` 等 `Referrer-Policy`；缺少 Referer 时服务端只能按路径形状校验，跨应用隔离会失效。
- `PUT` 必须显式带 `Content-Type: application/json`（可以带 `; charset=utf-8`）；只写 `.json` 文件，`state.json` 和 `articles/` 以外的路径会被拒绝。
- 请求体上限 1 MiB（1,048,576 字节）；`PUT` 必须带合法的 `Content-Length`。
- 请使用普通字符串或 `Blob` 作为 `body`，浏览器会自动带 `Content-Length`；流式 `ReadableStream` 会变成 chunked，服务端不支持。
- 只允许删除文件；删除目录、删除非 `.json` 文件都会被拒绝。
- `<name>` 不能包含 `/`、`\`，也不能是 `.` 或 `..`；建议只用字母、数字、`-`、`_`，例如 `a1`、`note-2026`。
- 不提供 CORS 头，也不支持 `POST`；页面必须从同一个本地服务加载。

### 状态码

- `201`：PUT 新建成功
- `204`：PUT 覆盖成功，或 DELETE 成功
- `400`：请求格式错误、`Content-Type` 不是 `application/json`、缺少/非法 `Content-Length`、路径穿越
- `403`：文件夹只读、目标不是 `.json`、越出当前应用目录、试图删除目录
- `404`：文件或应用目录不存在
- `405`：不支持的请求方法（例如 `POST`）
- `413`：请求体超过 1 MiB
- `500`：创建、写入或删除失败

错误响应是 `text/plain; charset=utf-8` 的简短中文说明，不含堆栈信息。页面应把响应文本显示给用户：

```js
if (!res.ok) alert(await res.text());
```

数据就是文件夹里的普通文件，关闭应用、重启手机、端口变化后仍然存在；备份所选文件夹即可备份数据。

## 打开方式

- 本地 Web 应用使用标准的 `ACTION_VIEW` 打开，由系统默认浏览器处理。
- 如果手机上有多个浏览器，会弹出系统选择器，可以选择「仅一次 / 始终」。
- 快捷链接同样按系统默认应用打开。

## 为什么用本地 HTTP 服务

浏览器无法直接读取应用私有目录中的文件，也无法通过单个 `content://` 授权解析 HTML 里的相对路径 JS/CSS。

因此应用引用 HTML 所在目录，并在 `127.0.0.1` 上启动一个临时静态文件服务：

```text
http://127.0.0.1:<随机端口>/t/<随机令牌>/app-one/index.html
```

这样以父目录为网站根目录，`app-one/style.css`、`app-one/js/app.js`、甚至 `../shared/...` 都能正确加载；修改原文件后刷新浏览器即可看到变化。随机端口和随机路径令牌降低被其他应用读取的风险。

同一个服务还提供受限的 JSON 写接口，只允许修改当前应用的 `state.json` 和 `articles/*.json`，不额外开端口。

说明：

- 需要把所选父目录理解为网站根目录；`../` 跳出该父目录的引用无法访问。
- 浏览器和本应用是分开的进程。打开页面时应用会启动一个低优先级的前台服务，避免浏览器在前台时本应用进程被系统回收；把应用从最近任务中划掉后服务会停止。Android 13 及以上若未授予通知权限，通知可能不显示在通知栏，但服务仍会运行。
- 文件夹授权会被持久保存，重启应用后仍然有效；如果原文件夹被移动/删除，需要重新导入。
- v1.12 起导入时会同时申请读、写权限，并做一次真实写入探测；旧版本保存的授权只有读权限，需要重新导入后 Web 应用才能保存数据。应用页的文件夹信息旁会显示「可写」或「只读」。
- 如果页面刷新时仍提示连接被拒绝，请回到应用重新点击一次文件。

## 重新构建

需要 JDK 17 和 Android SDK（compileSdk 35）：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug    # 调试版
./gradlew assembleRelease  # 发布版（需要 keystore.properties）
```

产物：

- 调试版：`app/build/outputs/apk/debug/app-debug.apk`
- 发布版：`app/build/outputs/apk/release/app-release.apk`

## 发布签名

- 公开证书：`signing/htmlopener-release-cert.pem`（只含公钥，可安全加入仓库）
- 密钥库：`release.keystore`（PKCS12，别名 `htmlopener`，RSA 2048，有效期 10000 天）
- 签名配置：`keystore.properties`（包含密钥库密码，已加入 `.gitignore`）
- 证书 SHA-256：`06f86bec15f725eea955a18f8138daf646a668d28f3c78dc2fc6c8d1b32eeccc`

**私钥密钥库和密码不会加入仓库，也不能提交到公开仓库。** 它们只应通过安全方式备份和复制。以后在本机或其他机器构建同签名 APK 时，必须使用同一份 `release.keystore`、相同的 `keyAlias` 和密码；`signing/htmlopener-release-cert.pem` 仅用于校验，不能用于签名。

### 多机编译同签名 APK

先把 `release.keystore` 和 `keystore.properties` 安全复制到另一台机器的项目根目录并限制权限：

```bash
chmod 600 release.keystore keystore.properties
```

然后执行：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew clean assembleRelease
```

### 校验 APK 签名

```bash
apksigner verify --print-certs HTMLOpener-release.apk
```

输出的 `Signer #1 certificate SHA-256 digest` 应与上面的证书 SHA-256 一致。也可以直接校验公开证书：

```bash
openssl x509 -in signing/htmlopener-release-cert.pem -noout -fingerprint -sha256
```

请把 `release.keystore` 和 `keystore.properties` 离线备份。以后发布更新必须使用同一签名；丢失后无法覆盖安装旧版本，只能卸载重装或更换包名。

## 主要文件

- `app/src/main/java/com/example/htmlopener/MainActivity.java`：目录导入与读写授权、只读提示、Web 应用列表、快捷链接、桌面快捷方式、底部导航
- `app/src/main/java/com/example/htmlopener/HtmlServer.java`：仅监听 `127.0.0.1` 的静态文件服务，以及受限的 JSON `PUT` / `DELETE` 写接口
- `app/src/main/java/com/example/htmlopener/ServerService.java`：保持服务进程存活的前台服务
- `app/src/main/res/layout/activity_main.xml`：页面容器和底部导航
- `app/src/main/res/layout/page_apps.xml`、`page_links.xml`：应用页、链接页
- `app/src/main/res/layout/item_app.xml`、`item_link.xml`、`dialog_link.xml`：列表行和链接对话框
- `app/src/main/res/values/colors.xml`、`styles.xml`：红黑白配色与主题
