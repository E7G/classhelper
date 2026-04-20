#!/bin/bash

echo "Downloading Paraformer Chinese ASR Model..."
echo

MODEL_DIR="assets/models/sherpa-onnx-paraformer-zh-2023-09-14"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2"

mkdir -p assets/models
mkdir -p "$MODEL_DIR"

echo "Downloading model from:"
echo "$MODEL_URL"
echo

curl -L -o paraformer.tar.bz2 "$MODEL_URL"

echo
echo "Extracting model..."
tar -xjf paraformer.tar.bz2 -C assets/models

rm paraformer.tar.bz2

echo
echo "Model downloaded successfully!"
echo "Model location: $MODEL_DIR"
echo
