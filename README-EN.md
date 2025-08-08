# JavaCLI - English Documentation

An intelligent conversational assistant in Java with tool integration capabilities through the Model Context Protocol (MCP).

[🏠 Main README](README.md) | [🇧🇷 Português](README-PT.md)

## 🚀 Key Features

- **Multiple LLM Providers**: Support for Groq, Gemini, Claude, OpenAI
- **Advanced Inference Strategies**: SimpleSequential, ReAct, ToolUse
- **MCP Integration**: Access to external tools (filesystem, weather, RSS, etc.)
- **Interactive CLI Interface**: Real-time chat with performance metrics
- **Modular Architecture**: Well-separated and extensible components

## 📋 Prerequisites

- **Java 17** or higher
- **Maven 3.6** or higher
- **Node.js** (for MCP servers)
- **Python 3.8+** (for some MCP servers)

## 🔧 Setup

### 1. Environment Variables

Configure the following environment variables with your API keys:

```bash
# At least one of the following is required
export GROQ_API_KEY="your_groq_api_key"
export GEMINI_API_KEY="your_gemini_api_key"  
export ANTHROPIC_API_KEY="your_claude_api_key"
export OPENAI_API_KEY="your_openai_api_key"
```

### 2. MCP Servers Configuration

MCP servers are automatically configured from the `config/mcp/.mcp.json` file. Available servers include:

- **Memory**: Temporary storage
- **Weather**: Weather forecasts
- **Filesystem**: File access
- **RSS**: News feeds
- **DateTime**: Date and time

## 🏗️ Installation and Execution

### Compilation

```bash
mvn clean compile
```

### Execution

```bash
mvn exec:java -Dexec.mainClass="com.gazapps.App"
```

### Creating Executable JAR

```bash
mvn clean package
java -jar target/JavaCLI-0.0.1-SNAPSHOT-jar-with-dependencies.jar
```

## 💬 Usage

1. Run the application
2. Type your questions or commands
3. The system automatically:
   - Analyzes if external tools are needed
   - Executes necessary tools
   - Generates a contextualized response

### Exit Commands

To exit the chat, use any of these commands:

- `exit`, `quit`, `bye`
- `sair`, `tchau`, `adeus`

## 🏛️ Architecture

### Main Components

- **ChatEngine**: Main conversation engine
- **LLM Services**: Abstraction for different providers
- **Inference Strategies**: Different processing approaches
- **MCP Integration**: Connection to external tools
- **Memory System**: Conversation context management

### Inference Strategies

1. **SimpleSequential**: Basic linear processing
2. **ReAct**: Reasoning-Acting with iterations
3. **ToolUse**: Intelligent tool selection

## 📁 Project Structure

```
JavaCLI/
├── src/main/java/com/gazapps/
│   ├── core/           # Main chat engine
│   ├── llm/            # LLM services
│   ├── inference/      # Processing strategies
│   ├── mcp/            # MCP integration
│   ├── config/         # Configuration
│   └── exceptions/     # Error handling
├── config/             # Configuration files
├── log/               # Application logs
└── target/            # Build artifacts
```

## 🔧 Advanced Configuration

### application.properties

Configure timeouts, URLs and models in the `config/application.properties` file:

```properties
# Groq Configuration
groq.model=llama-3.3-70b-versatile
groq.timeout=30

# Gemini Configuration  
gemini.model=gemini-2.0-flash
gemini.timeout=30
```

### Custom MCP Servers

Add new servers by editing `config/mcp/.mcp.json`:

```json
{
  "mcpServers": {
    "new-server": {
      "command": "server-command",
      "args": [],
      "description": "Server description",
      "enabled": true,
      "priority": 1
    }
  }
}
```

## 🧪 Usage Examples

```
You: What's the weather forecast for New York today?
🤖 Assistant: [Uses weather-nws server to fetch information] (⏱️ 2.34s)

You: Create a file weather,txt with the NYC weather information
🤖 Assistant: [Uses filesystem to create file] (⏱️ 1.87s)

You: Show me the latest tech news
🤖 Assistant: [Uses RSS feeds to fetch news] (⏱️ 3.12s)
```

## 🐛 Troubleshooting

### Common Issues

1. **"API Key not configured"**: Check environment variables
2. **"MCP connection timeout"**: Verify MCP servers are installed
3. **"Compilation error"**: Check if you have Java 17+ and Maven

### Logs

Logs are saved in:

- `log/application.log` - General logs
- `log/errors.log` - Error logs only

## 🎯 Development

### Running Tests

```bash
mvn test
```

### Building Documentation

```bash
mvn javadoc:javadoc
```

### Code Style

This project follows standard Java conventions. Please ensure:

- Proper javadoc comments
- Consistent indentation (4 spaces)
- Meaningful variable names
- Error handling for all external calls

## 🤝 Contributing

1. Fork the project
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

### Contribution Guidelines

- Follow existing code style
- Add tests for new features
- Update documentation
- Ensure all tests pass

## 🔒 Security

- API keys are never stored in code
- All sensitive configuration is excluded from version control
- MCP servers run in isolated processes
- Input validation prevents injection attacks

## 📊 Performance

- Typical response time: 1-5 seconds
- Memory usage: ~100MB base + LLM overhead
- Concurrent MCP connections: Up to 5 servers
- Maximum tool chain length: 3 tools

## 🔄 Changelog

### v0.0.1 (Initial Release)

- Multi-LLM provider support
- Three inference strategies
- MCP integration with 5 servers
- Interactive CLI interface
- Comprehensive error handling

## 📞 Support

For questions or issues:

- Open an issue on GitHub
- Check logs in `log/`
- Consult MCP server documentation
- Review configuration examples

## 🙏 Acknowledgments

- [Model Context Protocol](https://modelcontextprotocol.io/) for the integration framework
- [Anthropic](https://www.anthropic.com/) for Claude API
- [Google](https://ai.google.dev/) for Gemini API
- [Groq](https://groq.com/) for Groq API
- [OpenAI](https://openai.com/) for GPT models

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

**Built with ❤️ using Java 17 and MCP**
