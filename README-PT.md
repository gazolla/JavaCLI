# JavaCLI - Documentação em Português

Um assistente conversacional inteligente em Java com capacidades de integração de ferramentas através do Model Context Protocol (MCP).

[🏠 README Principal](README.md) | [🇺🇸 English](README-EN.md)

## 🚀 Características Principais

- **Múltiplos Provedores LLM**: Suporte para Groq, Gemini, Claude, OpenAI
- **Estratégias de Inferência Avançadas**: SimpleSequential, ReAct, ToolUse
- **Integração MCP**: Acesso a ferramentas externas (filesystem, weather, RSS, etc.)
- **Interface CLI Interativa**: Chat em tempo real com medição de performance
- **Arquitetura Modular**: Componentes bem separados e extensíveis

## 📋 Pré-requisitos

- **Java 17** ou superior
- **Maven 3.6** ou superior
- **Node.js** (para servidores MCP)
- **Python 3.8+** (para alguns servidores MCP)

## 🔧 Configuração

### 1. Variáveis de Ambiente

Configure as seguintes variáveis de ambiente com suas API keys:

```bash
# Pelo menos uma das seguintes é necessária
export GROQ_API_KEY="sua_groq_api_key"
export GEMINI_API_KEY="sua_gemini_api_key"  
export ANTHROPIC_API_KEY="sua_claude_api_key"
export OPENAI_API_KEY="sua_openai_api_key"
```

### 2. Configuração dos Servidores MCP

Os servidores MCP são automaticamente configurados a partir do arquivo `config/mcp/.mcp.json`. Os servidores incluem:

- **Memory**: Armazenamento temporário
- **Weather**: Previsões meteorológicas
- **Filesystem**: Acesso a arquivos
- **RSS**: Feeds de notícias
- **DateTime**: Data e hora

## 🏗️ Instalação e Execução

### Compilação

```bash
mvn clean compile
```

### Execução

```bash
mvn exec:java -Dexec.mainClass="com.gazapps.App"
```

### Criação do JAR executável

```bash
mvn clean package
java -jar target/JavaCLI-0.0.1-SNAPSHOT-jar-with-dependencies.jar
```

## 💬 Uso

1. Execute a aplicação
2. Digite suas perguntas ou comandos
3. O sistema automaticamente:
   - Analisa se precisa de ferramentas externas
   - Executa as ferramentas necessárias
   - Gera uma resposta contextualizada

### Comandos de Saída

Para sair do chat, use qualquer um dos comandos:

- `exit`, `quit`, `bye`
- `sair`, `tchau`, `adeus`

## 🏛️ Arquitetura

### Componentes Principais

- **ChatEngine**: Motor principal de conversação
- **LLM Services**: Abstração para diferentes provedores
- **Inference Strategies**: Diferentes abordagens de processamento
- **MCP Integration**: Conexão com ferramentas externas
- **Memory System**: Gerenciamento de contexto de conversa

### Estratégias de Inferência

1. **SimpleSequential**: Processamento linear básico
2. **ReAct**: Reasoning-Acting com iterações
3. **ToolUse**: Seleção inteligente de ferramentas

## 📁 Estrutura do Projeto

```
JavaCLI/
├── src/main/java/com/gazapps/
│   ├── core/           # Motor principal do chat
│   ├── llm/            # Serviços de LLM
│   ├── inference/      # Estratégias de processamento
│   ├── mcp/            # Integração MCP
│   ├── config/         # Configurações
│   └── exceptions/     # Tratamento de erros
├── config/             # Arquivos de configuração
├── log/               # Logs da aplicação
└── target/            # Build artifacts
```

## 🔧 Configurações Avançadas

### application.properties

Configure timeouts, URLs e modelos no arquivo `config/application.properties`:

```properties
# Configuração Groq
groq.model=llama-3.3-70b-versatile
groq.timeout=30

# Configuração Gemini  
gemini.model=gemini-2.0-flash
gemini.timeout=30
```

### Servidores MCP Customizados

Adicione novos servidores editando `config/mcp/.mcp.json`:

```json
{
  "mcpServers": {
    "novo-servidor": {
      "command": "comando-do-servidor",
      "args": [],
      "description": "Descrição do servidor",
      "enabled": true,
      "priority": 1
    }
  }
}
```

## 🧪 Exemplos de Uso

```
Você: Qual é a previsão do tempo para NYC hoje?
🤖 Assistant: [Usa o servidor weather-nws para buscar informações] (⏱️ 2.34s)

Você: Crie um arquivo com as informações do clima
🤖 Assistant: [Usa filesystem para criar arquivo] (⏱️ 1.87s)

Você: Me mostre as últimas notícias de tecnologia
🤖 Assistant: [Usa RSS feeds para buscar notícias] (⏱️ 3.12s)
```

## 🐛 Solução de Problemas

### Problemas Comuns

1. **"API Key não configurada"**: Verifique as variáveis de ambiente
2. **"Timeout na conexão MCP"**: Verifique se os servidores MCP estão instalados
3. **"Erro de compilação"**: Verifique se tem Java 17+ e Maven

### Logs

Os logs são salvos em:

- `log/application.log` - Logs gerais
- `log/errors.log` - Apenas erros

## 🎯 Desenvolvimento

### Executando Testes

```bash
mvn test
```

### Construindo Documentação

```bash
mvn javadoc:javadoc
```

### Estilo de Código

Este projeto segue as convenções padrão do Java. Certifique-se de:

- Comentários javadoc adequados
- Indentação consistente (4 espaços)
- Nomes de variáveis significativos
- Tratamento de erro para todas as chamadas externas

## 🤝 Contribuição

1. Faça um Fork do projeto
2. Crie uma branch para sua feature
3. Commit suas mudanças
4. Faça Push para a branch
5. Abra um Pull Request

### Diretrizes de Contribuição

- Siga o estilo de código existente
- Adicione testes para novas funcionalidades
- Atualize a documentação
- Certifique-se de que todos os testes passem

## 🔒 Segurança

- API keys nunca são armazenadas no código
- Toda configuração sensível é excluída do controle de versão
- Servidores MCP executam em processos isolados
- Validação de entrada previne ataques de injeção

## 📊 Performance

- Tempo de resposta típico: 1-5 segundos
- Uso de memória: ~100MB base + overhead do LLM
- Conexões MCP concorrentes: Até 5 servidores
- Comprimento máximo da cadeia de ferramentas: 3 ferramentas

## 🔄 Changelog

### v0.0.1 (Lançamento Inicial)

- Suporte a múltiplos provedores LLM
- Três estratégias de inferência
- Integração MCP com 5 servidores
- Interface CLI interativa
- Tratamento abrangente de erros

## 📞 Suporte

Para dúvidas ou problemas:

- Abra uma issue no GitHub
- Verifique os logs em `log/`
- Consulte a documentação dos servidores MCP
- Revise os exemplos de configuração

## 🙏 Agradecimentos

- [Model Context Protocol](https://modelcontextprotocol.io/) pelo framework de integração
- [Anthropic](https://www.anthropic.com/) pela API Claude
- [Google](https://ai.google.dev/) pela API Gemini
- [Groq](https://groq.com/) pelaAPI Groq
- [OpenAI](https://openai.com/) pelos modelos GPT

## 📄 Licença

Este projeto está licenciado sob a Licença MIT - veja o arquivo [LICENSE](LICENSE) para detalhes.

---

**Desenvolvido com ❤️ usando Java 17 e MCP**
