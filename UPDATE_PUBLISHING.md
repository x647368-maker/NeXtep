# ZMW小武的最简发布步骤

本版本没有自动公开发布任何 Release。APK 检查来源固定为 x647368-maker/NeXtep，原版 NeXtep 或无校验元数据的历史 Release 不会触发安装。

## 发布一次新版本

1. 开发时递增 `versionCode`，使用原签名密钥编译并签名。开发版使用 `X.Y.Z-dev`，稳定版使用 `X.Y.Z`。
2. 准备已签名 APK、对应 `.apk.sha256`、`atomic-update.json` 三个文件。本次交付 ZIP 已附带这三项。元数据与 APK 必须属于同一版本，不要手填或复用旧校验值。
3. 打开 https://github.com/x647368-maker/NeXtep/releases/new ，新建对应版本标签，填写更新说明，上传上述三个文件。开发版勾选 Pre-release，稳定版不勾。最后由 ZMW小武自行确认 Publish release。

用户打开设置/关于页手动检查，或自选开启自动检测后，App 按 `versionCode` 找更新。自动检测只在打开设置时进行，间隔至少 12 小时，不是远程强推、常驻后台或静默安装。有通知权限时发通知，否则仍保留 App 内提示。下载通过验证后由 Android 系统请求确认；升级模块后仍需正常重启。

## 生成可靠发布文件

使用已有 JDK 和 Android SDK 的官方 apksigner.jar：

```bash
python3 scripts/prepare-update.py --apk StageManager-X.Y.Z.apk --apksigner /path/to/apksigner.jar --notes RELEASE_NOTES.md --out release-ready
```

脚本从 APK 读取真实包名、版本码和版本名，验证原开发证书，生成 SHA-256 与元数据，不上传任何文件、不发布任何版本。未签名或签名不符会失败。

GitHub Actions 可以准备同样的文件，但仅当仓库配置好原固定签名。必须使用私密 Actions Secrets：`ATOMIC_KEYSTORE_BASE64`、`ATOMIC_KEYSTORE_PASSWORD`、`ATOMIC_KEY_PASSWORD`。不要把密钥、密码或 Token 放到源码、Release、APK 或聊天公开内容中。当前构建未配置签名时只产出 unsigned 校验包，本次最终 APK 在私有环境用原密钥签名。

## 元数据格式

固定文件名 `atomic-update.json`，schemaVersion=1，字段包括 applicationId、versionName、versionCode、channel（dev/stable）、apkAsset（与上传 APK 文件名一致）、sha256、size、certificateSha256、releaseNotes。

客户端首先核对本仓库的 Release/资产地址与大小，再验证下载内容及 Android 包解析结果。metadata 中的 versionCode 必须大于已安装版本，签名必须与已安装开发证书相同。校验不通过拒绝交给安装器；网络失败可以重试。App 从不索取仓库写权限，也不需要任何 GitHub Token。

本次没有真机验证 Android 17 的权限页、通知投递和系统安装交互，发布前应在测试设备走完一次用户确认流程。离线签名、包身份、摘要和发布准备结果可自动验证。
