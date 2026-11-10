# Jev Android 原生评分运行时

`build-android.ps1` 使用固定的 llama.cpp 提交 `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`，构建 Android arm64-v8a、API 30 CPU 版 `llama-server`。产物以 `libjevserver.so` 命名，经 APK 的 `jniLibs` 解包到 `nativeLibraryDir`，由应用的 `ProcessBuilder` 执行。它是 ELF PIE 可执行程序，不使用 `System.loadLibrary`。

## 构建

安装 JDK 17、Android SDK 包 `ndk;28.2.13676358` 与 `cmake;3.22.1`。默认查找项目 `build/.local-tools/android-sdk`，也可用参数传入 SDK 目录：

```powershell
./tools/jev/build-android.ps1 -SdkRoot D:/Android/Sdk -Jobs 4
```

Windows 中文路径使用临时 N: 盘符映射；若已被占用，传入 `-DriveLetter` 指定空闲盘符。脚本不改系统环境变量。第三方源码及中间产物放在被忽略的 `build/` 下，提交固定版本的脚本、许可证及生成的运行时二进制。`runtime-manifest.json` 记录实际二进制 SHA-256。

构建使用静态 libc++、静态 ggml/llama，关闭 OpenSSL、下载 Web UI 和子进程工具；仅保留系统 Bionic 依赖。ARM 指令集使用基础 `armv8-a`，不要求 SVE、SME 或 dot-product。ELF LOAD 段按 16 KiB 对齐。`android-main.cpp` 补父进程退出信号及竞态检查，应用被终止时同时杀死评分进程。

## 应用调用契约

启动参数示例（文件名和端口由应用提供）：

```text
<nativeLibraryDir>/libjevserver.so
  -m <app-private-dir>/MiniCPM-OCR-Jev-Q4_K_M.gguf
  --mmproj <app-private-dir>/mmproj-model-f16.gguf
  --host 127.0.0.1 --port 0
  --api-key <每次会话随机密钥>
  -c 4096 -np 1 -b 512 -ub 128 -ngl 0 -t 4
  --no-webui --no-warmup
```

应用必须限制为回环地址，每次启动创建新密钥，HTTP 请求附 `Authorization: Bearer <密钥>`；不能通过用户配置修改地址，也不记录完整命令行或密钥。使用 `--port 0` 由原生进程直接绑定空闲端口，只从自己启动的进程输出中解析 `listening on http://127.0.0.1:<端口>` 后连接。加载期间 `/health` 返回 503，加载完成为 200，不能把模型加载完成当成一次评分成功。应用退出、主动取消或请求超时时销毁该进程，避免旧请求仍继续占用 CPU。

直接调用的是原生 **`POST /v1/systemone`**。官方 Python SDK 的 `/v1/score` 是另一层包装，不能直接对这个二进制调用。

```json
{
  "state": "待校验的 OCR 原文",
  "images": [
    "data:image/png;base64,<原始区域图片>",
    "data:image/png;base64,<OCR原文渲染图>"
  ],
  "questions": {
    "ocr": { "type": "noul" }
  }
}
```

Jev 必须有且只有两张图，顺序为原始区域、候选文字渲染图。只支持一个问题，不传 `instructions` 或 `criteria`；GGUF 内的 `systemone` 模板负责真正提示词，输入中的控制 token 会按上游实现转义。

成功响应中的 **`answers.ocr.noul`** 是 0–1 有限浮点数，表示候选与原图一致的概率。缺字段、非数值、HTTP 错误、加载失败和超时都必须报告“未评分”，不能用 0 代替。输出不包含生成文本，模型不支持 `/v1/chat/completions`。

## 使用边界

- 先验证并准备模型和视觉编码器两个文件，运行时本身不负责联网下载权重。
- 每次只处理一个区域，限制图片尺寸与文字长度，避免把多页文档直接放进 4096 上下文。
- 渲染字体、字号、布局影响模型分数；Android 本地渲染要以目标样本验证，不能直接沿用桌面服务阈值或宣称同样的准确率。
- 这是 OCR 正确性校验器，分数不能解释为“GUI 操作成功概率”。

参考：[官方部署文档](https://github.com/OpenBMB/MiniCPM-OCR/blob/main/docs/llamacpp_deploy_jev_en.md)、[固定版本原生接口](https://github.com/ggml-org/llama.cpp/blob/a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5/tools/server/README.md#post-v1systemone-typesafe-compatible-system-one-api)。
