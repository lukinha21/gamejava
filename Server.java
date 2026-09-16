import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;

/**
 * Servidor HTTP minimalista (sem frameworks, só a biblioteca padrão do
 * Java) para servir a aplicação web "Teste de Precisão do Mouse"
 * (pasta public/).
 *
 * Uso:
 *   java Server.java            -> escuta na porta 8080
 *   java Server.java 9090       -> escuta na porta 9090
 *   PORT=9090 java Server.java  -> escuta na porta definida em PORT
 */
public class Server {

    private static final Path PUBLIC_DIR = Paths.get("public").toAbsolutePath().normalize();

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(null); // executor padrão (uma thread por requisição)
        server.start();

        System.out.println("Servidor rodando em http://0.0.0.0:" + port);
        System.out.println("Servindo arquivos de: " + PUBLIC_DIR);
    }

    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                // segue para variável de ambiente / padrão
            }
        }
        String envPort = System.getenv("PORT");
        if (envPort != null) {
            try {
                return Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) {
                // usa padrão
            }
        }
        return 8080;
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            if (requestPath.equals("/")) {
                requestPath = "/index.html";
            }

            Path filePath = PUBLIC_DIR.resolve(requestPath.substring(1)).normalize();

            // Proteção simples contra path traversal (ex: ../../etc/passwd)
            boolean valid = filePath.startsWith(PUBLIC_DIR) && Files.exists(filePath) && !Files.isDirectory(filePath);

            if (!valid) {
                byte[] notFound = "404 - Não encontrado".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound);
                }
                return;
            }

            // A página principal é RENDERIZADA dinamicamente a cada
            // requisição (igual ao EJS do app.js de exemplo): o Java
            // calcula IP e hora do servidor e injeta no HTML antes de
            // enviar. Os demais arquivos (se houver) são servidos como
            // estáticos, sem processamento.
            if (requestPath.equals("/index.html")) {
                renderDynamicPage(exchange, filePath);
                return;
            }

            byte[] bytes = Files.readAllBytes(filePath);
            exchange.getResponseHeaders().set("Content-Type", contentTypeFor(filePath));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void renderDynamicPage(HttpExchange exchange, Path filePath) throws IOException {
            String template = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);

            String html = template
                    .replace("{{SERVER_IP}}", getServerIp())
                    .replace("{{SERVER_TIME}}", getServerTime());

            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        /** Equivalente Java do getIpAddress() do app.js: primeiro IPv4 não-interno. */
        private String getServerIp() {
            try {
                Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                while (ifaces.hasMoreElements()) {
                    NetworkInterface iface = ifaces.nextElement();
                    if (iface.isLoopback() || !iface.isUp()) continue;
                    Enumeration<InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            } catch (SocketException ignored) {
                // cai para o valor padrão abaixo
            }
            return "Endereço IP não encontrado";
        }

        /** Equivalente Java do moment().format('YYYY-MM-DD HH:mm:ss') do app.js. */
        private String getServerTime() {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        private String contentTypeFor(Path path) {
            String name = path.toString().toLowerCase();
            if (name.endsWith(".html")) return "text/html; charset=utf-8";
            if (name.endsWith(".css")) return "text/css; charset=utf-8";
            if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (name.endsWith(".png")) return "image/png";
            if (name.endsWith(".svg")) return "image/svg+xml";
            if (name.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}
