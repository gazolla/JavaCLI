#!/bin/bash

echo "🔄 Recompiling ToolUse with LLM Implementation..."
echo "================================================="

# Navigate to project directory
cd "C:\Users\gazol\AppData\MCP\WRKGRP\JavaCLI"

# Clean and compile
echo "🧹 Cleaning previous build..."
mvn clean -q

echo "📦 Compiling new implementation..."
mvn compile -q

if [ $? -eq 0 ]; then
    echo "✅ Compilation successful!"
    echo ""
    echo "🎯 New LLM-driven ToolUse is now active"
    echo "Key features:"
    echo "- ✅ LLM parameter extraction with schema"
    echo "- ✅ Auto-retry with error correction"  
    echo "- ✅ Domain knowledge (timezones, URLs)"
    echo "- ✅ MAX_RETRIES = 2"
    echo "- ✅ DEFAULT_TIMEZONE = America/Sao_Paulo"
    echo ""
    echo "🧪 Test the previously failing cases:"
    echo "java -cp target/classes com.gazapps.App"
else
    echo "❌ Compilation failed!"
    echo "Check errors above and fix before testing."
    exit 1
fi
