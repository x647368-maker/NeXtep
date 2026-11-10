# 台前调度更新索引

本分支仅存储供客户端读取的 `index.json`，**不是安装包发布渠道**。开始时列表为空，不会向用户推送任何未经确认的构建。

后续只有用户批准、原证书签名并发布到 `x647368-maker/NeXtep` GitHub Releases 的版本才可加入 `releases`。索引每条记录含 `atomic-update.json` 的全部字段及对应 `releaseTag`。客户端仍按包名、版本码、SHA-256 和原证书进行本地安装前校验。

此分支不用于日常开发，也不存放私钥、密码或 Android APK。
