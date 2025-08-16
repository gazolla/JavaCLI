package com.gazapps.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gazapps.config.Config;
import com.gazapps.exceptions.ConfigException;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;

public class MCPServers implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(MCPServers.class);

	public List<MCPService.ServerConfig> mcpServers = new ArrayList<>();
	public final Map<String, McpSyncClient> mcpClients = new HashMap<>();
	private final Map<String, MCPService.ServerStatus> serverStatuses = new HashMap<>();
	private final Map<String, String> toolToServer = new HashMap<>();
	private final Map<String, Tool> availableMcpTools = new HashMap<>();

	private final MCPService mcpService;
	public int requestTimeoutSeconds = 30;

	public MCPServers(MCPService mcpService) {
		this.mcpService = mcpService;
	}

	public void loadServers() throws ConfigException {
		Config configLoader = new Config();
		List<MCPService.ServerConfig> loadedConfigs = configLoader.loadServerConfigs();

		if (!loadedConfigs.isEmpty()) {
			mcpServers.addAll(loadedConfigs);
			logger.info("📅 {} MCP servers loaded from configuration", loadedConfigs.size());
			return; 
		}
		
		logger.warn("⚠️ No MCP servers found in configuration file");
	}

	private boolean checkCommand(String... command) {
		try {
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			Process process = pb.start();
			return process.waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}
	
	/**
	 * Verifica se um servidor NPX específico está disponível
	 */
	private boolean checkNpxServer(String serverCommand) {
		try {
			// Extrai o nome do pacote do comando (ex: "npx @modelcontextprotocol/server-filesystem" -> "@modelcontextprotocol/server-filesystem")
			String packageName = serverCommand.replace("npx ", "").split(" ")[0];
			
			// Verifica se o pacote existe no registry npm (timeout curto)
			ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "npm", "view", packageName, "version");
			pb.redirectErrorStream(true);
			Process process = pb.start();
			
			// Timeout mais curto para verificação
			boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				return false;
			}
			
			return process.exitValue() == 0;
		} catch (Exception e) {
			logger.debug("Erro verificando servidor NPX {}: {}", serverCommand, e.getMessage());
			return false;
		}
	}

	public void processServerDependencies() {
		boolean isNodeAvailable = checkCommand("cmd.exe", "/c", "npx", "--version");
		boolean isInternetAvailable = checkCommand("ping", "-n", "1", "8.8.8.8");
		boolean isDockerAvailable = checkCommand("docker", "--version");

		logger.info("🔍 Verificando dependências dos servidores MCP...");
		logger.info("Node.js: {}", (isNodeAvailable ? "✅ Disponível" : "❌ Não encontrado"));
		logger.info("Internet: {}", (isInternetAvailable ? "✅ Conectado" : "❌ Offline"));
		logger.info("Docker: {}", (isDockerAvailable ? "✅ Disponível" : "❌ Não encontrado"));

		for (MCPService.ServerConfig server : mcpServers) {
			processServerDependency(server, isNodeAvailable, isInternetAvailable, isDockerAvailable);
		}

		mcpServers.sort((a, b) -> Integer.compare(a.priority.getValue(), b.priority.getValue()));

		long enabledCount = mcpServers.stream().filter(s -> s.enabled).count();
		logger.info("📊 Resultado: {}/{} servidores habilitados", enabledCount, mcpServers.size());
	}

	private boolean hasRequiredEnvironment(MCPService.ServerConfig server) {
		String requiredEnv = server.environment.get("REQUIRES_ENV");
		if (requiredEnv == null)
			return true;

		String envValue = System.getenv(requiredEnv);
		return envValue != null && !envValue.trim().isEmpty();
	}

	private void processServerDependency(MCPService.ServerConfig server, boolean nodeJsAvailable,
			boolean internetAvailable, boolean dockerAvailable) {
		
		// Se o servidor está desabilitado na configuração, não processar
		if (!server.enabled) {
			logger.info("⏭️  {:<12} [{}] {} (desabilitado na configuração)", server.name, server.priority, server.description);
			return;
		}
		
		List<String> missingDeps = new ArrayList<>();

		if (server.environment.containsKey("REQUIRES_NODEJS") && !nodeJsAvailable) {
			missingDeps.add("Node.js");
		}

		if (server.environment.containsKey("REQUIRES_ONLINE") && !internetAvailable) {
			missingDeps.add("Internet");
		}

		if (server.environment.containsKey("REQUIRES_DOCKER") && !dockerAvailable) {
			missingDeps.add("Docker");
		}

		if (!hasRequiredEnvironment(server)) {
			String requiredEnv = server.environment.get("REQUIRES_ENV");
			missingDeps.add("Env:" + requiredEnv);
		}

		if (!missingDeps.isEmpty()) {
			server.enabled = false;
			logger.warn("❌ {:<12} [{}] {} (falta: {})", server.name, server.priority, server.description,
					String.join(", ", missingDeps));
		} else {
			// Mantém habilitado apenas se passou em todas as verificações E estava habilitado na config
			logger.info("✅ {:<12} [{}] {}", server.name, server.priority, server.description);
		}
	}

	public void connectToServers() {
		logger.info("🔌 Conectando aos servidores MCP...");

		for (MCPService.ServerConfig serverConfig : mcpServers) {
			if (!serverConfig.enabled) {
				logger.info("⏭️  Pulando {} (dependências não atendidas)", serverConfig.name);
				continue;
			}

			if (!hasRequiredEnvironment(serverConfig)) {
				String requiredEnv = serverConfig.environment.get("REQUIRES_ENV");
				logger.info("⏭️  Pulando {} (variável {} não configurada)", serverConfig.name, requiredEnv);
				serverStatuses.put(serverConfig.name, MCPService.ServerStatus.DISCONNECTED);
				continue;
			}

			connectToServer(serverConfig);
		}
	}

	private void connectToServer(MCPService.ServerConfig serverConfig) {
		try {
			logger.info("📡 Conectando a {}...", serverConfig.name);

			McpSyncClient client = mcpService.connectToServer(serverConfig);

			mcpClients.put(serverConfig.name, client);
			serverStatuses.put(serverConfig.name, MCPService.ServerStatus.CONNECTED);

			discoverToolsFromServer(serverConfig.name, client);

			logger.info(" ✅");

		} catch (Exception e) {
			logger.error("Erro inesperado: ", e);
			String errorMessage = e.getMessage() != null ? e.getMessage() : "Erro desconhecido";
			logger.error("❌ Falha ao conectar a {}: {}", serverConfig.name, errorMessage);
			serverStatuses.put(serverConfig.name, MCPService.ServerStatus.ERROR);
		}
	}

	private void discoverToolsFromServer(String serverName, McpSyncClient client) {
		try {
			List<Tool> serverTools = mcpService.discoverServerTools(client);

			for (Tool mcpTool : serverTools) {
				String toolName = mcpTool.name();
				String namespacedToolName = serverName + "_" + toolName;
				toolToServer.put(namespacedToolName, serverName);
				availableMcpTools.put(namespacedToolName, mcpTool);
			}
		} catch (Exception e) {
			logger.error("Erro ao descobrir tools: ", e);
		}
	}

	public String getServerForTool(String namespacedToolName) {
		return toolToServer.get(namespacedToolName);
	}

	public McpSyncClient getClient(String serverName) {
		return mcpClients.get(serverName);
	}

	public List<MCPService.ServerConfig> getConnectedServers() {
		return mcpServers.stream().filter(server -> server.enabled && mcpClients.containsKey(server.name))
				.collect(java.util.stream.Collectors.toList());
	}

	@Override
	public void close() {
		logger.info("🔌 Fechando conexões...");

		for (Map.Entry<String, McpSyncClient> entry : mcpClients.entrySet()) {
			try {
				entry.getValue().close();
				logger.info("✅ {} desconectado", entry.getKey());
			} catch (Exception e) {
				logger.error("Erro ao desconectar: ", e);
				logger.error("⚠️ Erro ao desconectar {}: {}", entry.getKey(), e.getMessage());
			}
		}
	}
}
