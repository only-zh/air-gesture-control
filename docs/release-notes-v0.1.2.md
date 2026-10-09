**本版本没有任何功能改动。** APK 与 0.1.1 除了版本号之外完全一致 —— 发这一版是为了打通自动发版流程，并用一个真实的 Release 验证它。

如果你在用 0.1.1 且一切正常，**不需要升级**。

---

## 下载

到本页附件区下载 `air-gesture-control-v0.1.2.apk`（约 94.5 MB，两个模型已内置）。

该文件的 **SHA-256 由发布工作流自动计算并附在本说明末尾**，下载后可以比对。

与之前所有版本**签名相同**，可直接覆盖安装，不需要卸载。

```bash
adb install -r air-gesture-control-v0.1.2.apk
```

---

## 这一版做了什么

全在工程侧，与 App 行为无关：

### 自动发版

新增两条 GitHub Actions：

| 工作流 | 触发 | 作用 |
|---|---|---|
| `ci.yml` | main 推送、所有 PR | 跑单元测试 + 编译。**刻意不下载模型** —— 测试和编译都不需要那 73MB，所以跑得很快 |
| `release.yml` | 推送 `v*` 标签 | 下载模型 → 跑测试 → 构建 release 包 → 创建 Release 并上传 APK |

**这个 Release 本身就是 `release.yml` 的产物。**

工作流内置三道守门员，任何一条不过就中止，避免发出错误的包：

1. 标签必须与 `app/build.gradle.kts` 里的 `versionName` 一致
   （防「打了标签忘了改版本号」—— 这个错在 0.1.1 上真实发生过一次）
2. 发布说明文件 `docs/release-notes-<tag>.md` 必须存在
3. 签名密钥 Secret 必须已配置，否则会产出未签名、装不上的 APK

### 其它工程改动

- **补上标准的 Gradle wrapper**（`gradlew`），CI 与贡献者可以直接构建
- **签名配置改为「密钥文件不存在就不注册」**：fork 或未配 Secret 的环境只是产出未签名包，
  而不会构建失败；发版流程则显式校验 Secret，避免误发
- **`.gitattributes` 让 Windows 批处理保留 CRLF** —— `gradlew.bat` 被转成 LF 后
  在部分 Windows 环境下会执行失败

---

## 已知限制（与 0.1.1 相同）

- **语音与隔空手势两条链路尚未真机验证**；抖音的五个核心动作已在真机跑通
- 暂停功能依赖无障碍的持续按压模拟，不同机型表现可能不一致
- 抖音改版会导致文案定位失效，需用内置的节点探测页重新校准
- 高频自动点赞可能触发平台风控，已默认开启节流
- 国产 ROM 会杀后台无障碍服务，需加电池优化白名单

---

## 签名说明

本版本使用 **debug 签名密钥**（口令为标准 Android debug 口令 `android`），
任何人都能重新打包一个签名相同的 APK，**不适合正式分发**。

完整变更见 [CHANGELOG](https://github.com/only-zh/air-gesture-control/blob/main/CHANGELOG.md)。
