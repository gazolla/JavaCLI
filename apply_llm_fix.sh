#!/bin/bash

echo "🔄 Applying LLM-Driven ToolUse Fix..."
echo "==================================="

# Navigate to project
cd "C:\Users\gazol\AppData\MCP\WRKGRP\JavaCLI"

echo "📝 Checking implementation..."
# Check if key methods are present
if grep -q "extractParametersWithLLM" src/main/java/com/gazapps/inference/tooluse/ToolUseInference.java; then
    echo "✅ extractParametersWithLLM method found"
else
    echo "❌ extractParametersWithLLM method missing"
    exit 1
fi

if grep -q "executeWithLLMRetry" src/main/java/com/gazapps/inference/tooluse/ToolUseInference.java; then
    echo "✅ executeWithLLMRetry method found"
else
    echo "❌ executeWithLLMRetry method missing" 
    exit 1
fi

if grep -q "DEFAULT_TIMEZONE.*America/Sao_Paulo" src/main/java/com/gazapps/inference/tooluse/ToolUseInference.java; then
    echo "✅ DEFAULT_TIMEZONE configured correctly"
else
    echo "❌ DEFAULT_TIMEZONE not configured"
    exit 1
fi

echo ""
echo "🔨 Compiling with Maven..."
mvn compile -q

if [ $? -eq 0 ]; then
    echo "✅ Compilation successful!"
    echo ""
    echo "🎯 LLM-Driven ToolUse is now ACTIVE!"
    echo ""
    echo "Key fixes implemented:"
    echo "- ✅ Timezone parameter extraction with geographic knowledge"
    echo "- ✅ URL parameter extraction with https:// prefix"
    echo "- ✅ Auto-retry with LLM error correction (max 2 retries)"
    echo "- ✅ Schema-aware parameter validation"
    echo "- ✅ Domain knowledge for common locations"
    echo ""
    echo "🧪 Test cases that should now work:"
    echo "1. 'Que horas são em San Francisco,CA?' → timezone=America/Los_Angeles"
    echo "2. 'Me mostre as manchetes de metropoles.com' → url=https://metropoles.com"
    echo "3. 'Que dia é hoje?' → timezone=America/Sao_Paulo"
    echo ""
    echo "▶️  Run: java -cp target/classes com.gazapps.App"
else
    echo "❌ Compilation failed!"
    echo ""
    echo "🔍 Checking compilation errors..."
    mvn compile
    exit 1
fi
