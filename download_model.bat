@echo off
echo ========================================
echo 下载语音识别模型
echo ========================================
echo.

set MODEL_NAME=sherpa-onnx-streaming-paraformer-bilingual-zh-en
set MODEL_DIR=assets\models\%MODEL_NAME%

if not exist %MODEL_DIR% mkdir %MODEL_DIR%

echo 正在下载 Paraformer 中英文语音识别模型...
echo.

powershell -Command "& {Invoke-WebRequest -Uri 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/%MODEL_NAME%.tar.bz2' -OutFile '%MODEL_NAME%.tar.bz2'}"

if exist %MODEL_NAME%.tar.bz2 (
    echo 正在解压模型文件...
    tar -xf %MODEL_NAME%.tar.bz2
    
    echo 正在移动文件...
    move /Y %MODEL_NAME%\*.* %MODEL_DIR%\
    rmdir /S /Q %MODEL_NAME%
    del %MODEL_NAME%.tar.bz2
    
    echo.
    echo ========================================
    echo 语音识别模型下载完成！
    echo 模型位置: %MODEL_DIR%
    echo ========================================
) else (
    echo 下载失败，请检查网络连接
)

echo.
echo ========================================
echo LLM 模型已内置
echo ========================================
echo.
echo LLM 模型 (Qwen3.5-0.8B-Q4_K_M.gguf) 已包含在项目中
echo 模型位置: assets\models\Qwen3.5-0.8B-Q4_K_M.gguf
echo 下载来源: https://www.modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/files
echo.
echo 如需更换模型，请下载其他 GGUF 格式模型并替换该文件
echo ========================================

pause
