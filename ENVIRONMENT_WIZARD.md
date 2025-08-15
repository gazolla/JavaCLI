# Environment Wizard - JavaCLI

Este documento demonstra como usar o Environment Wizard implementado no JavaCLI para configuração automática de API keys.

## Visão Geral

O Environment Wizard implementa uma solução KISS (Keep It Simple, Stupid) com uma única classe principal `EnvironmentSetup.java` que:

- ✅ Verifica automaticamente se existem chaves de API configuradas na inicialização
- ✅ Executa wizard interativo para coletar e configurar chaves quando necessário  
- ✅ Suporta configuração inline durante operações de hot-swap
- ✅ Valida formato e conectividade das chaves de API
- ✅ Persiste configurações no arquivo .env e carrega para System properties
- ✅ Mantém compatibilidade total com o sistema existente

## Funcionalidades Implementadas

### 1. Verificação Automática na Inicialização

```java
// Em App.java - verificação automática antes de criar ChatEngine
if (!EnvironmentSetup.ensureApiKeysConfigured()) {
    throw new ConfigurationException("Application cannot start without API key configuration");
}
```

### 2. Wizard Completo para Novos Usuários

Quando nenhuma chave é encontrada, executa wizard interativo:

```
🚀 JavaCLI Environment Setup Wizard
══════════════════════════════════════════════════
Welcome! Let's configure your LLM API keys to get started.
You need at least one API key to use JavaCLI.

📋 Available LLM Providers:
1. Groq (Llama 3.3 70B)
2. Google Gemini (2.0 Flash)  
3. Anthropic Claude (3.5 Sonnet)
4. OpenAI (GPT-4)

🔧 Configure Groq (Llama 3.3 70B)? (y/n/q to quit): y

🔑 Setting up Groq (Llama 3.3 70B)
────────────────────────────────────────
To get your API key:
1. Go to https://console.groq.com/keys
2. Sign in or create account
3. Click 'Create API Key'
4. Copy the key (starts with 'gsk_')

Enter your Groq (Llama 3.3 70B) API key: gsk_...
🔄 Validating API key...
✅ API key is valid and working!
✅ Groq (Llama 3.3 70B) configured successfully!
```

### 3. Configuração Inline Durante Hot-Swap

```java
// Em CommandProcessor.java - verificação antes de trocar provedor
if (!EnvironmentSetup.isProviderConfigured(provider)) {
    if (!EnvironmentSetup.setupProviderInline(provider)) {
        return CommandResult.error("❌ Configuration cancelled. Cannot switch to " + provider + ".");
    }
}
```

Exemplo de uso:
```
You: /llm claude

🔧 Anthropic Claude (3.5 Sonnet) API Key Required
──────────────────────────────────────────────────
To use Anthropic Claude (3.5 Sonnet), you need to configure an API key.
Would you like to configure it now? (y/n): y

🔑 Setting up Anthropic Claude (3.5 Sonnet)
────────────────────────────────────────────
To get your API key:
1. Go to https://console.anthropic.com/account/keys
2. Sign in or create account
3. Click 'Create Key'
4. Copy the key (starts with 'sk-ant-')

Enter your Anthropic Claude (3.5 Sonnet) API key: sk-ant-...
🔄 Validating API key...
✅ API key is valid and working!
✅ LLM changed to Anthropic Claude (3.5 Sonnet)
💾 Conversation history preserved
⏱️ Ready
```

### 4. Validação em Duas Camadas

#### Validação de Formato
```java
// Padrões regex para cada provedor
KEY_PATTERNS.put("groq", Pattern.compile("^gsk_[A-Za-z0-9]{52}$"));
KEY_PATTERNS.put("gemini", Pattern.compile("^AIza[A-Za-z0-9_-]{35}$"));
KEY_PATTERNS.put("claude", Pattern.compile("^sk-ant-[A-Za-z0-9_-]{95,105}$"));
KEY_PATTERNS.put("openai", Pattern.compile("^sk-[A-Za-z0-9]{48}$"));
```

#### Validação de Conectividade
Faz requisições de teste para as APIs de cada provedor para confirmar que as chaves funcionam.

### 5. Gerenciamento de Configuração

#### Estrutura de Arquivos
```
JavaCLI/
├── config/
│   ├── application.properties  # Configurações dos provedores
│   └── .env                    # Chaves de API (criado automaticamente)
```

#### Carregamento de Configurações
```java
// Carrega do .env para System properties
loadEnvFile();

// Configurações dos provedores vêm do application.properties
private static void initializeProviders() {
    Properties props = loadApplicationProperties();
    
    PROVIDERS.put("groq", new ProviderInfo(
        "groq",
        "Groq (Llama 3.3 70B)",
        "GROQ_API_KEY",
        props.getProperty("groq.base.url", "https://api.groq.com/openai/v1/chat/completions"),
        props.getProperty("groq.model", "llama-3.3-70b-versatile"),
        "gsk_",
        "1. Go to https://console.groq.com/keys\n2. Sign in or create account...",
        Integer.parseInt(props.getProperty("groq.timeout", "30"))
    ));
}
```

### 6. Modificações Mínimas nas Classes Existentes

#### App.java
```java
// Apenas uma verificação antes da criação do ChatEngine
if (!EnvironmentSetup.ensureApiKeysConfigured()) {
    throw new ConfigurationException("Application cannot start without API key configuration");
}
```

#### CommandProcessor.java
```java
// Verificação antes de hot-swap
if (!EnvironmentSetup.isProviderConfigured(provider)) {
    if (!EnvironmentSetup.setupProviderInline(provider)) {
        return CommandResult.error("❌ Configuration cancelled.");
    }
}
```

#### RuntimeConfigManager.java
```java
// Integração de validação
String providerName = llmService.getProviderName().toLowerCase();
if (!EnvironmentSetup.isProviderConfigured(providerName)) {
    logger.warn("Provider {} is not properly configured", providerName);
    return false;
}
```

#### LlmBuilder.java
```java
// Resolução automática de chaves
private static String getApiKeyFromEnvironment(String envVarName) {
    // System properties first (loaded from .env)
    String key = System.getProperty(envVarName);
    if (key != null && !key.trim().isEmpty()) {
        return key;
    }
    
    // Environment variables second
    key = System.getenv(envVarName);
    return key;
}
```

## Tratamento de Erros

### ConfigurationException Personalizada
```java
public class ConfigurationException extends Exception {
    private final String provider;
    private final String configType;
    
    public String getUserFriendlyMessage() {
        if (provider != null && configType != null) {
            return String.format("Configuration error for %s (%s): %s", 
                                provider, configType, getMessage());
        }
        return String.format("Configuration error: %s", getMessage());
    }
}
```

### Fallbacks e Recovery
- Timeout na validação de conectividade assume chave válida
- Validação de formato como primeira linha de defesa
- Máximo de 3 tentativas para inserir chaves válidas
- Mensagens de erro amigáveis e instrucionais

## Compatibilidade

- ✅ Zero breaking changes no código existente
- ✅ Sistema de hot-swap preservado e melhorado
- ✅ Todas as funcionalidades atuais mantidas
- ✅ Configuração manual via environment variables ainda funciona
- ✅ Prioridade: environment variables > .env file

## Benefícios

### Para Novos Usuários
- Setup zero-config com wizard guiado
- Instruções claras sobre onde obter chaves
- Validação em tempo real
- Experiência amigável

### Para Usuários Avançados
- Configuração manual ainda disponível
- Environment variables têm prioridade
- Controle fino via application.properties
- Integração com sistemas existentes

### Para Desenvolvedores
- Código limpo e bem estruturado (KISS)
- Funcionalidade centralizada em uma classe
- Logging detalhado para debugging
- Extensível para novos provedores

## Uso

### Primeira Execução
```bash
java -jar JavaCLI.jar
# Wizard automático será executado se nenhuma chave for encontrada
```

### Hot-Swap com Configuração Inline
```
You: /llm gemini
# Se não configurado, oferece setup inline automaticamente
```

### Verificação de Status
```
You: /config
📊 Current Configuration:
🤖 LLM: Groq (Llama 3.3 70B Versatile)
🧠 Inference: ToolUseInference
💾 Memory: 5 messages preserved
🔧 MCP Tools: Available
⚡ Status: Ready
```

O Environment Wizard transforma JavaCLI em uma aplicação verdadeiramente plug-and-play, mantendo toda a flexibilidade para usuários avançados.
