# 签名兼容与迁移边界

## 已核实

- 7.4.0 run-219 是不可调试的 release APK。
- 证书 SHA-256 为 `650a17f2bbc6d3cf7ac436e3ce7d4cbc1381cfd29052d6a8e06e70361ef48e8e`，与 7.1.3 发布工作流锁定的指纹相同。
- 证书主题为 `CN=Android Debug, O=Android, C=US`。主题名称不是私钥泄露证据，也不能证明密钥的保管安全、备份完整或历史上从未泄露。
- 当前构建从仓库 Secrets 使用既有私钥；检查公开证书不需要读取私钥。不得导出 Secrets 到日志、下载产物或聊天。

## 当前措施

- CI 在 apksigner 完成密码学验证后，额外核对既有证书指纹；缺失或变化即失败。
- 不自动生成或替换生产私钥，不更改包名，不要求卸载旧版。
- 这只解决误换签名风险，不把历史调试证书改称商店级正式证书。
- GitHub APK 分发与应用商店接收政策分别判断。正式发布仍需真机验收和维护者明确批准。

## 独立迁移的前置条件

1. 确认新私钥的持久安全保存、离线备份及 Secrets 写入权限；不在临时 Runner 生成唯一且不可恢复的生产私钥。
2. 保留旧私钥与旧 Secrets；验证新证书及轮换谱系后再考虑切换。
3. 根据最低支持 API 26 设计兼容签名：不能把支持密钥轮换的较新 Android 结论推广到 Android 8。
4. 在 API 26/27、支持轮换的 Android 和目标 OPPO 上分别验证从旧版覆盖安装、数据保留、权限及通知行为；未实测不承诺兼容。
5. 新分发包增加 versionCode，迁移单独评审，不混入普通发布收尾。

## 官方依据

- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/tools/apksigner
- https://source.android.com/docs/security/features/apksigning/v3-1
