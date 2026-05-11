import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Date; // Used for message timestamps
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// MongoDB Imports
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.mindrot.jbcrypt.BCrypt; 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Metrics and Monitoring Imports (Prometheus/Micrometer)
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class Server implements Runnable {

    private Map<String, ConnectionHandler> connections;
    private ServerSocket server;
    private boolean done;
    private ExecutorService pool;
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    
    // Metrics configuration
    private static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private static final Counter messagesCounter = Counter.builder("chat_messages_total")
            .description("Total messages sent in chat")
            .register(registry);
    
    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> messageCollection;
    private MongoCollection<Document> userCollection;

    public Server() {
        connections = new ConcurrentHashMap<>();
        // Registering a Gauge to monitor online users in real-time
        Gauge.builder("chat_users_online", connections, Map::size)
        .description("Current users online")
        .register(registry);
        done = false;
    }

    @Override
    public void run() {
        try {
            // Retrieve DB Host from environment variables (useful for Docker/Production)
            String dbHost = System.getenv("DB_HOST");
            if (dbHost == null) dbHost = "localhost";

            // Initialize MongoDB connection
            mongoClient = MongoClients.create("mongodb://" + dbHost + ":27017");
            database = mongoClient.getDatabase("chatDB");
            messageCollection = database.getCollection("messages");
            userCollection = database.getCollection("users");
            logger.info("Connected to MongoDB successfully!");

            // Initialize Server Socket and Thread Pool
            server = new ServerSocket(9999);
            pool = Executors.newCachedThreadPool();
            
            // Setting up internal HTTP server to expose Prometheus metrics
            try {
                HttpServer metricsServer = HttpServer.create(new InetSocketAddress(8080), 0);
                metricsServer.createContext("/metrics", httpExchange -> {
                    String response = registry.scrape();
                    httpExchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                    httpExchange.sendResponseHeaders(200, response.getBytes().length);
                    try (OutputStream os = httpExchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });
                new Thread(metricsServer::start).start();
                logger.info("Metrics server started on port 8080");
            } catch (IOException e) {
                logger.error("Failed to start metrics server: " + e.getMessage());
            }
            
            // Accept incoming client connections
            while (!done) {
                Socket client = server.accept();
                ConnectionHandler handler = new ConnectionHandler(client);
                pool.execute(handler);
            }
        } catch (Exception e) {
            shutdown();
        }
    }

    /**
     * Persists chat messages to MongoDB asynchronously to avoid blocking the main thread.
     */
    private void saveMessageToDB(String user, String message, String room) {
        // Task is delegated to the thread pool so the current thread can continue immediately
        pool.execute(() -> {
            try {
                if (messageCollection != null) {
                    Document doc = new Document("user", user)
                            .append("message", message)
                            .append("room", room)
                            .append("timestamp", new Date());
                    messageCollection.insertOne(doc);
                }
            } catch (Exception e) {
                logger.error("Async DB Error: " + e.getMessage());
            }
        });
    }
    
    /**
     * Broadcasts a message to all users currently in a specific room.
     */
    public void broadcast(String message, String room) {
        messagesCounter.increment(); 

        // No explicit synchronization needed as 'connections' is a ConcurrentHashMap
        for (ConnectionHandler ch : connections.values()) {
            if (ch != null && ch.currentRoom.equals(room)) {
                ch.sendMessage(message);
            }
        }
    }

    /**
     * Gracefully shuts down the server and cleans up resources.
     */
    public void shutdown() {
        try {
            done = true;
            if (mongoClient != null) mongoClient.close();
            if (pool != null) pool.shutdown();
            if (server != null && !server.isClosed()) {
                server.close();
            }
            if (connections != null) {
                for (ConnectionHandler ch : connections.values()) {
                    ch.shutdown();
                }               
            }
        } catch (IOException e) {
            // Ignore closure errors
        }
    }

    /**
     * Inner class to handle individual client logic and communication.
     */
    class ConnectionHandler implements Runnable {
        private Socket client;
        private BufferedReader in;
        private PrintWriter out;
        private String nickname;
        private String normalizedName; // For case-insensitive storage in connections map
        private String currentRoom = "General";

        public ConnectionHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(client.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                
                // Authentication Loop: Handles both Login and Registration
                while (true) {
                    out.println("Welcome! Enter Nickname to Login or Register: ");
                    String inputName = in.readLine();
                    if (inputName == null) return; // Client disconnected during authentication
                    nickname = inputName.strip();
                    normalizedName = nickname.toLowerCase();

                    // Check if user exists in MongoDB
                    Document existingUser = userCollection.find(new Document("username", normalizedName)).first();

                    if (existingUser != null) {
                        // User exists -> Login Process
                        out.println("Enter password for " + nickname + ": ");
                        String password = in.readLine();
                        String hashed = existingUser.getString("password");

                        if (BCrypt.checkpw(password, hashed)) {
                            // Password matches -> Check for double login
                            if (isAlreadyLoggedIn(normalizedName)) {
                                out.println("User is already logged in from another device!");
                                continue;
                            }
                            break; // Authentication Successful
                        } else {
                            out.println("Wrong password! Try again.");
                        }
                    } else {
                        // New user -> Registration Process
                        out.println("Nickname not found. Create a new password to register: ");
                        String newPassword = in.readLine();
                        if (newPassword != null && newPassword.length() >= 4) {
                            String hashed = BCrypt.hashpw(newPassword, BCrypt.gensalt());
                            userCollection.insertOne(new Document("username", normalizedName).append("password", hashed));
                            out.println("Registration successful!");
                            break;
                        } else {
                            out.println("Password too short! Try again.");
                        }
                    }
                }

                // Finalize connection setup
                connections.put(normalizedName, this);
                logger.info(nickname + " connected!");
                broadcast(nickname + " joined the chat!", currentRoom);

                // Asynchronously fetch and send chat history to the new user
                pool.execute(() -> sendChatHistory());
                
                // Main Message Handling Loop
                String rawInput;
                while ((rawInput = in.readLine()) != null) {
                    String message = sanitize(rawInput);
                    if (message.startsWith("/msg ")) {
                        // Private message format: /msg <nickname> <content>
                        String[] split = message.split(" ", 3);
                        if (split.length >= 3) {
                            String targetNick = split[1];
                            String privateContent = split[2];
                            sendPrivateMessage(nickname, targetNick, privateContent);
                        } else {
                            sendMessage("[System] Usage: /msg <nickname> <message>");
                        }
                    }
                    else if (message.startsWith("/join ")) {
                        // Room switching logic
                        String[] split = message.split(" ", 2);
                        if (split.length == 2) {
                            String newRoom = split[1].trim();
                            
                            // Notify old room about departure
                            broadcast(nickname + " left the room.", currentRoom);
                            
                            // Switch room
                            currentRoom = newRoom;
                            
                            // Notify new room about arrival
                            broadcast(nickname + " joined the room: " + currentRoom, currentRoom);
                            sendMessage("[System] Switched to room: " + currentRoom);
                            
                            // Fetch history for the specific new room
                            pool.execute(() -> sendChatHistory());
                        }
                    }
                    else if (message.startsWith("/nick ")) {
                        // Nickname change logic
                        String[] messageSplit = message.split(" ", 2);
                        if (messageSplit.length == 2) {
                            broadcast(nickname + " changed to " + messageSplit[1], currentRoom);
                            nickname = messageSplit[1];
                        }
                    } else if (message.startsWith("/quit")) {
                        break;
                    } else {
                        // Standard global broadcast and DB archival
                        broadcast(nickname + ": " + message, currentRoom);
                        saveMessageToDB(nickname, message, currentRoom);
                    }
                }
            } catch (IOException e) {
                logger.info("Connection lost with " + nickname);
            } finally {
                handleDisconnect();
            }
        }

        /**
         * Fetches the last 10 messages from the database for the current room.
         */
        private void sendChatHistory() {
            try {
                Document filter = new Document("room", currentRoom);
                out.println("--- Last 10 messages in " + currentRoom + " ---");
                // Querying DB: Filter by room, sort by time descending, limit to 10
                for (Document doc : messageCollection.find(filter).sort(Sorts.descending("timestamp")).limit(10)) {
                    out.println(doc.getString("user") + ": " + doc.getString("message"));
                }
                out.println("-------------------------");
            } catch (Exception e) {
                out.println("[System] Chat history is temporarily unavailable.");
            }
        }
        
        public void sendMessage(String message) {
            out.println(message);
        }
        
        /**
         * Sends a message to a specific user.
         */
        public void sendPrivateMessage(String senderNick, String targetNick, String message) {
            ConnectionHandler target = connections.get(targetNick.toLowerCase());
            if (target != null) {
                target.sendMessage("[Private from " + senderNick + "]: " + message);
                // Confirm sent message to the sender
                this.sendMessage("[Private to " + targetNick + "]: " + message);
            } else {
                this.sendMessage("[System] User " + targetNick + " not found.");
            }
        }
        
        /**
         * Validates if a nickname is already active in the connections map.
         */
        private boolean isAlreadyLoggedIn(String name) {
            ConnectionHandler existing = connections.get(name.toLowerCase());
            return (existing != null && existing != this);
        }

        /**
         * Cleans up the connection when a user disconnects.
         */
        private void handleDisconnect() {
            if (normalizedName != null) {
                connections.remove(normalizedName);
                broadcast(nickname + " left the chat!", currentRoom);
                logger.info("User {} disconnected", nickname);
            }
            shutdown();
        }

        public void shutdown() {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (client != null && !client.isClosed()) client.close();
            } catch (IOException e) { 
                // Ignore cleanup errors
            }
        }

        /**
         * Sanitizes user input by escaping special HTML characters.
         * This prevents Cross-Site Scripting (XSS) attacks by ensuring 
         * the browser treats the input as literal text rather than executable code.
         */
        private String sanitize(String input) {
            if (input == null) return null;

            // Replace special characters with their corresponding HTML entities
            return input.replace("&", "&amp;")   // Must be first to avoid double-escaping
                        .replace("<", "&lt;")    // Prevents opening HTML tags
                        .replace(">", "&gt;")    // Prevents closing HTML tags
                        .replace("\"", "&quot;") // Prevents breaking out of HTML attributes
                        .replace("'", "&#x27;"); // Prevents breaking out of JavaScript strings
        }
    }

    public static void main(String[] args) {
        Server server = new Server();
        server.run();
    }
}