# 模型资源目录

这个目录里的模型**不入版本库**（加起来 73MB），需要本地下载：

| 文件 / 目录 | 大小 | 获取方式 |
|---|---|---|
| `hand_landmarker.task` | 7.8 MB | `tools/setup-toolchain.sh` 自动下载 |
| `model-cn/` | 解包后 65 MB | `tools/fetch-vosk-model.sh` 下载并解包 |

来源：

- `hand_landmarker.task` — MediaPipe 官方模型
  `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`
- `vosk-model-small-cn-0.22` — Vosk 中文小模型
  `https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip`

两个模型都遵循各自的原始许可（均为 Apache-2.0）。

## 为什么语音模型要单独一个下载脚本

Vosk 官方源对单连接限速到约 30KB/s，44MB 要下 25 分钟。
`tools/fetch-vosk-model.sh` 用 8 路并行 Range 请求下载再拼接，实测快 5–10 倍，
并且支持断点续传（某一段失败只补那一段，不会从头再来）。
