# Correção do Problema de Servidores MCP

## 🔍 **Problema Identificado**

O sistema estava mostrando servidores errados (GitHub ativado, filesystem ausente) devido a um **bug na lógica de verificação de dependências**.

### **Causa Raiz:**

No método `processServerDependency()`, a configuração original do JSON estava sendo **sobrescrita**:

```java
// ❌ PROBLEMA: Ignorava a configuração original 
if (!missingDeps.isEmpty()) {
    server.enabled = false;
} else {
    server.enabled = true;  // ← Forçava true mesmo se estava false no JSON!
}
```

### **Consequências:**

1. **GitHub** configurado como `enabled: false` → Era forçado para `true` porque não tinha dependências faltando
2. **Filesystem** configurado como `enabled: true` → Pode ter sido desabilitado por falha na conexão

## ✅ **Correção Implementada**

### **1. Respeitar Configuração Original:**
```java
// ✅ CORREÇÃO: Verifica se já está desabilitado na configuração
if (!server.enabled) {
    logger.info("⏭️ {} (desabilitado na configuração)", server.name);
    return; // Não processa se já está desabilitado
}

// Só desabilita se passar de habilitado → desabilitado por dependências
if (!missingDeps.isEmpty()) {
    server.enabled = false;
} else {
    // Mantém habilitado (não força true)
    logger.info("✅ {} pronto", server.name);
}
```

### **2. Melhorar Configuração Padrão:**
```json
{
  "github": {
    "enabled": false,  // ← Explicitamente desabilitado
    "env": {
      "REQUIRES_NODEJS": "true",
      "REQUIRES_ENV": "GITHUB_TOKEN"  // Requer token
    }
  },
  "filesystem": {
    "enabled": true,   // ← Explicitamente habilitado
    "env": {
      "REQUIRES_NODEJS": "true"
    }
  }
}
```

### **3. Logs Mais Claros:**
- `⏭️ servidor (desabilitado na configuração)` - Pula servidores disabled
- `❌ servidor (falta: Node.js)` - Desabilitado por dependência
- `✅ servidor pronto` - Habilitado e funcionando

## 🎯 **Resultado Esperado**

Agora o comando `/tools` deve mostrar:
- ✅ **filesystem** - Habilitado (sistema de arquivos)
- ✅ **memory** - Habilitado (se Node.js disponível)  
- ✅ **weather-nws** - Habilitado (se Node.js + Internet disponível)
- ❌ **github** - Desabilitado (configuração + falta token)

## 🧪 **Como Testar**

1. Execute o JavaCLI
2. Observe os logs de inicialização - devem mostrar status correto
3. Use `/tools` - deve listar apenas servidores realmente conectados
4. Use `/status` - deve mostrar contagem precisa

A correção garante que a **configuração do usuário seja respeitada** e apenas servidores com dependências satisfeitas sejam habilitados.
