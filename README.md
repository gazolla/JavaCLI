# JavaCLI

<div align="center">

![Java](https://img.shields.io/badge/Java-17+-orange.svg)
![Maven](https://img.shields.io/badge/Maven-3.6+-blue.svg)
![MCP](https://img.shields.io/badge/MCP-0.10.0-green.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

**An intelligent conversational assistant in Java with tool integration capabilities through the Model Context Protocol (MCP)**

[🇺🇸 English README](README-EN.md) | [🇧🇷 README em Português](README-PT.md)

</div>

---

## 🚀 Quick Overview

JavaCLI is a command-line chatbot that combines multiple LLM providers with external tool integration through MCP (Model Context Protocol). It features multiple inference strategies and can execute real-world actions through connected tools.

### ✨ Key Features

- 🤖 **Multi-LLM Support**: Groq, Gemini, Claude, OpenAI
- 🧠 **Smart Inference**: Sequential, ReAct, ToolUse strategies  
- 🔧 **MCP Integration**: Filesystem, Weather, RSS, Memory tools
- ⚡ **Interactive CLI**: Real-time chat with performance metrics
- 🏗️ **Modular Architecture**: Clean, extensible design

### 🎯 Quick Start

```bash
# Set your API key
export GROQ_API_KEY="your_api_key_here"

# Run the application
mvn exec:java -Dexec.mainClass="com.gazapps.App"
```

---

## 📚 Documentation

Choose your preferred language for detailed documentation:

### 🇺🇸 [English Documentation](README-EN.md)

Complete setup guide, architecture overview, and usage examples in English.

### 🇧🇷 [Documentação em Português](README-PT.md)

Guia completo de configuração, visão geral da arquitetura e exemplos de uso em Português.

---

## 🤝 Contributing

We welcome contributions in any language! Please check our documentation for contribution guidelines.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

<div align="center">
<strong>Built with ❤️ using Java 17 and MCP</strong>
</div>
