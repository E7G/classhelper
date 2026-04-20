@echo off
echo ========================================
echo 编译 llama.cpp 共享库
echo ========================================
echo.

set LLAMA_DIR=ref\llama.cpp

if not exist %LLAMA_DIR% (
    echo 正在克隆 llama.cpp 仓库...
    git clone https://github.com/ggerganov/llama.cpp.git %LLAMA_DIR%
)

echo 正在编译 llama.dll ...
echo.

cd %LLAMA_DIR%

if not exist build mkdir build
cd build

cmake .. -DBUILD_SHARED_LIBS=ON -DLLAMA_CURL=OFF -DGGML_CUDA=OFF -DGGML_OPENCL=OFF -DGGML_METAL=OFF
cmake --build . --config Release

echo.

if exist Release\llama.dll (
    echo 编译成功！
    echo 正在复制 llama.dll 到项目目录...
    
    copy /Y Release\llama.dll ..\..\..\llama.dll
    copy /Y Release\llama.dll ..\..\..\assets\lib\llama.dll
    
    echo.
    echo ========================================
    echo llama.dll 已复制到项目目录
    echo ========================================
) else if exist llama.dll (
    echo 编译成功！
    echo 正在复制 llama.dll 到项目目录...
    
    copy /Y llama.dll ..\..\..\llama.dll
    if not exist ..\..\..\assets\lib mkdir ..\..\..\assets\lib
    copy /Y llama.dll ..\..\..\assets\lib\llama.dll
    
    echo.
    echo ========================================
    echo llama.dll 已复制到项目目录
    echo ========================================
) else (
    echo 编译失败，请检查错误信息
)

cd ..\..\..

echo.
pause
