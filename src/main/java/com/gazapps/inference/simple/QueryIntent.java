package com.gazapps.inference.simple;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Enum abrangente para classificação de intenções de query.
 * Cobre 35+ categorias diferentes para matching preciso com ferramentas MCP.
 */
public enum QueryIntent {
    
    // 📊 DADOS EXTERNOS (External Data Queries)
    WEATHER("weather", "clima", "tempo", "temperatura", "chuva", "sol", "nublado", "forecast", "precipitation", "humidity", "rain", "snow", "wind"),
    FINANCE("preço", "price", "stock", "ação", "bolsa", "mercado", "investimento", "bitcoin", "crypto", "moeda", "dollar", "euro", "real", "trading"),
    NEWS("notícia", "news", "jornal", "acontecimento", "evento", "breaking", "latest", "headlines", "atualizações", "mídia", "imprensa"),
    CURRENCY("câmbio", "exchange", "conversão", "dólar", "euro", "real", "rate", "currency", "forex", "convert", "exchange-rate"),
    TRAFFIC("trânsito", "traffic", "rota", "route", "direção", "navigation", "maps", "caminho", "estrada", "gps", "directions"),
    SPORTS("esporte", "sports", "futebol", "basketball", "football", "soccer", "game", "match", "score", "resultado", "league"),
    
    // ⏰ TEMPORAL (Time & Date Queries)  
    DATETIME("hora", "time", "data", "date", "dia", "hoje", "agora", "now", "current", "atual", "when", "what time"),
    TIMEZONE("fuso", "timezone", "horário", "zone", "UTC", "GMT", "local", "brasília", "new york", "london"),
    SCHEDULE("agenda", "schedule", "calendário", "calendar", "compromisso", "appointment", "meeting", "reunião", "event"),
    REMINDER("lembrete", "reminder", "alarme", "alarm", "notificação", "alert", "aviso", "notify", "warn"),
    TIMER("timer", "cronômetro", "countdown", "stopwatch", "tempo", "duration", "minutes", "seconds", "hours"),
    
    // 📁 SISTEMA E ARQUIVOS (System & File Operations)
    FILE_LIST("listar", "list", "arquivos", "files", "pasta", "folder", "directory", "conteúdo", "contents", "dir", "ls"),
    FILE_CREATE("criar", "create", "novo", "new", "arquivo", "file", "pasta", "folder", "directory", "make", "mkdir"),
    FILE_DELETE("deletar", "delete", "apagar", "remove", "excluir", "remover", "rm", "del", "erase"),
    FILE_COPY("copiar", "copy", "cp", "duplicar", "duplicate", "clone", "backup"),
    FILE_MOVE("mover", "move", "mv", "transferir", "transfer", "renomear", "rename", "relocate"),
    FILE_EDIT("editar", "edit", "modificar", "modify", "alterar", "change", "update", "write", "save"),
    FILE_SEARCH("buscar", "search", "encontrar", "find", "procurar", "localizar", "locate", "grep", "look for"),
    FILE_READ("ler", "read", "ver", "view", "mostrar", "show", "display", "cat", "open", "content"),
    SYSTEM_INFO("sistema", "system", "info", "status", "cpu", "memory", "disk", "space", "usage", "performance", "monitor"),
    
    // 🔍 BUSCA E PESQUISA (Search & Research)
    WEB_SEARCH("google", "search", "buscar", "pesquisar", "procurar", "web", "internet", "lookup", "find online"),
    LOCAL_SEARCH("perto", "nearby", "próximo", "local", "restaurante", "hotel", "loja", "store", "restaurant"),
    DATABASE_SEARCH("database", "db", "query", "sql", "select", "banco", "dados", "records", "table"),
    KNOWLEDGE("explicar", "explain", "o que é", "what is", "como", "how", "definir", "define", "conceito", "concept"),
    
    // 📧 COMUNICAÇÃO E NOTIFICAÇÕES (Communication)
    EMAIL("email", "mail", "enviar", "send", "message", "carta", "correio", "inbox", "spam", "attachment"),
    SMS("sms", "texto", "text", "mensagem", "whatsapp", "telegram", "message", "phone"),
    CALL("ligar", "call", "telefone", "phone", "chamar", "contact", "dial", "number"),
    NOTIFICATION("notificar", "notify", "avisar", "alert", "push", "notification", "warning", "inform"),
    SOCIAL_MEDIA("facebook", "twitter", "instagram", "linkedin", "social", "post", "share", "like", "follow"),
    
    // 🧮 CÁLCULOS E CONVERSÕES (Calculations)
    MATH("calcular", "calculate", "matemática", "math", "soma", "add", "subtract", "multiply", "divide", "equation"),
    UNIT_CONVERSION("converter", "convert", "metros", "feet", "celsius", "fahrenheit", "kg", "pounds", "units"),
    FINANCE_CALC("juros", "interest", "loan", "empréstimo", "mortgage", "investment", "return", "profit", "tax"),
    
    // 🎨 CRIAÇÃO DE CONTEÚDO (Content Creation)
    IMAGE_EDIT("imagem", "image", "photo", "picture", "resize", "crop", "filter", "edit", "compress"),
    VIDEO_EDIT("vídeo", "video", "movie", "film", "edit", "cut", "merge", "convert", "compress"),
    DOCUMENT_CREATE("documento", "document", "pdf", "word", "report", "relatório", "create", "generate"),
    PRESENTATION("apresentação", "presentation", "slides", "powerpoint", "deck", "slideshow"),
    DESIGN("design", "logo", "banner", "layout", "graphic", "visual", "create", "make"),
    
    // 🏢 BUSINESS/ENTERPRISE (Business Tools)
    ANALYTICS("analytics", "análise", "metrics", "kpi", "dashboard", "report", "data", "statistics", "trends"),
    HR("rh", "hr", "employee", "funcionário", "payroll", "salary", "vacation", "hiring", "recruitment"),
    INVENTORY("estoque", "inventory", "stock", "warehouse", "products", "items", "supplies", "materials"),
    CRM("customer", "cliente", "crm", "sales", "vendas", "lead", "contact", "prospect", "pipeline"),
    
    // 💻 DEVELOPER TOOLS (Development)
    CODE_REVIEW("code", "código", "review", "pull request", "pr", "commit", "git", "github", "merge"),
    DEPLOY("deploy", "deployment", "release", "publish", "production", "staging", "build", "ci/cd"),
    TEST("test", "teste", "unit", "integration", "bug", "debug", "qa", "quality", "check"),
    MONITOR("monitor", "log", "error", "exception", "performance", "uptime", "server", "application"),
    DATABASE("database", "db", "sql", "query", "table", "insert", "update", "delete", "backup"),
    
    // 🏠 IOT/SMART HOME (Internet of Things)
    SMART_LIGHTS("luz", "light", "lamp", "brightness", "dim", "on", "off", "illuminate", "bulb"),
    CLIMATE_CONTROL("temperatura", "thermostat", "heating", "cooling", "ac", "air conditioning", "climate"),
    SECURITY("security", "camera", "alarm", "lock", "unlock", "door", "window", "motion", "sensor"),
    APPLIANCES("appliance", "eletrodoméstico", "tv", "microwave", "washing machine", "dishwasher", "oven"),
    
    // 🏥 HEALTH/FITNESS (Health & Wellness)
    FITNESS("fitness", "exercise", "workout", "gym", "running", "weight", "calories", "steps", "activity"),
    HEALTH_MONITOR("health", "saúde", "pressure", "heart rate", "glucose", "medication", "symptoms"),
    NUTRITION("nutrition", "diet", "food", "meal", "recipe", "calories", "protein", "vitamins"),
    WELLNESS("meditation", "sleep", "stress", "relaxation", "mindfulness", "therapy", "mental health"),
    
    // 🌍 LOCATION & TRAVEL (Geography & Travel)
    LOCATION("location", "localização", "where", "address", "coordinates", "gps", "place", "position"),
    TRAVEL("travel", "viagem", "flight", "hotel", "booking", "reservation", "trip", "vacation", "tourism"),
    MAPS("maps", "mapa", "direction", "route", "navigation", "distance", "path", "way"),
    
    // 🎮 ENTERTAINMENT (Entertainment & Media)
    MUSIC("music", "música", "song", "playlist", "spotify", "play", "pause", "volume", "artist"),
    GAMES("game", "jogo", "play", "score", "level", "achievement", "gaming", "console"),
    MOVIES("movie", "filme", "cinema", "netflix", "watch", "streaming", "series", "episode"),
    
    // 🔒 KNOWLEDGE (Direct Knowledge - No Tools Needed)
    GENERAL_KNOWLEDGE("capital", "history", "science", "geography", "biology", "physics", "chemistry", "literature");
    
    private final Set<String> keywords;
    
    QueryIntent(String... keywords) {
        this.keywords = Set.of(keywords);
    }
    
    public Set<String> getKeywords() {
        return keywords;
    }
    
    /**
     * Detecta a intenção da query baseada em palavras-chave.
     * Retorna a primeira intenção que match ou GENERAL_KNOWLEDGE como fallback.
     */
    public static QueryIntent detectIntent(String query) {
        if (query == null || query.trim().isEmpty()) {
            return GENERAL_KNOWLEDGE;
        }
        
        String lowerQuery = query.toLowerCase();
        
        // Busca por todas as intenções (exceto GENERAL_KNOWLEDGE)
        for (QueryIntent intent : values()) {
            if (intent == GENERAL_KNOWLEDGE) continue;
            
            if (intent.keywords.stream().anyMatch(lowerQuery::contains)) {
                return intent;
            }
        }
        
        return GENERAL_KNOWLEDGE;
    }
    
    /**
     * Detecta múltiplas intenções para queries compostas.
     */
    public static List<QueryIntent> detectMultipleIntents(String query) {
        if (query == null || query.trim().isEmpty()) {
            return List.of(GENERAL_KNOWLEDGE);
        }
        
        String lowerQuery = query.toLowerCase();
        List<QueryIntent> intents = new ArrayList<>();
        
        for (QueryIntent intent : values()) {
            if (intent == GENERAL_KNOWLEDGE) continue;
            
            if (intent.keywords.stream().anyMatch(lowerQuery::contains)) {
                intents.add(intent);
            }
        }
        
        return intents.isEmpty() ? List.of(GENERAL_KNOWLEDGE) : intents;
    }
    
    /**
     * Verifica se a intenção requer ferramentas externas.
     */
    public boolean requiresTools() {
        return this != GENERAL_KNOWLEDGE;
    }
    
    /**
     * Agrupa intenções por domínio para filtering de tools.
     */
    public String getDomain() {
        return switch (this) {
            case WEATHER, FINANCE, NEWS, CURRENCY, TRAFFIC, SPORTS -> "external_data";
            case DATETIME, TIMEZONE, SCHEDULE, REMINDER, TIMER -> "temporal";
            case FILE_LIST, FILE_CREATE, FILE_DELETE, FILE_COPY, FILE_MOVE, 
                 FILE_EDIT, FILE_SEARCH, FILE_READ, SYSTEM_INFO -> "filesystem";
            case WEB_SEARCH, LOCAL_SEARCH, DATABASE_SEARCH -> "search";
            case EMAIL, SMS, CALL, NOTIFICATION, SOCIAL_MEDIA -> "communication";
            case MATH, UNIT_CONVERSION, FINANCE_CALC -> "calculation";
            case IMAGE_EDIT, VIDEO_EDIT, DOCUMENT_CREATE, PRESENTATION, DESIGN -> "content_creation";
            case ANALYTICS, HR, INVENTORY, CRM -> "business";
            case CODE_REVIEW, DEPLOY, TEST, MONITOR, DATABASE -> "development";
            case SMART_LIGHTS, CLIMATE_CONTROL, SECURITY, APPLIANCES -> "iot";
            case FITNESS, HEALTH_MONITOR, NUTRITION, WELLNESS -> "health";
            case LOCATION, TRAVEL, MAPS -> "location";
            case MUSIC, GAMES, MOVIES -> "entertainment";
            case KNOWLEDGE, GENERAL_KNOWLEDGE -> "knowledge";
        };
    }
}
