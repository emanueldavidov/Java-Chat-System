import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Date; // בשביל תאריך ההודעה
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// אימפורטים של MongoDB
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.mindrot.jbcrypt.BCrypt; 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server implements Runnable {

    private Map<String, ConnectionHandler> connections;
    private ServerSocket server;
    private boolean done;
    private ExecutorService pool;
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    // אובייקטים לניהול מסד הנתונים
    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> messageCollection;
    private MongoCollection<Document> userCollection;
    

    public Server() {
        connections = new ConcurrentHashMap<>();
        done = false;
    }

    @Override
    public void run() {
        try {
        	String dbHost = System.getenv("DB_HOST");
        	if (dbHost == null) dbHost = "localhost";

        	mongoClient = MongoClients.create("mongodb://" + dbHost + ":27017");
            database = mongoClient.getDatabase("chatDB");
            messageCollection = database.getCollection("messages");
            userCollection = database.getCollection("users");
            logger.info("Connected to MongoDB successfully!");

            server = new ServerSocket(9999);
            pool = Executors.newCachedThreadPool();
            while (!done) {
                Socket client = server.accept();
                ConnectionHandler handler = new ConnectionHandler(client);
                pool.execute(handler);
            }
        } catch (Exception e) {
            shutdown();
        }
    }

    private void saveMessageToDB(String user, String message, String room) {
        // אנחנו שולחים את משימת השמירה ל-Pool, הטרד הנוכחי משתחרר מיד!
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
    
    public void broadcast(String message, String room) {
        // אנחנו נועלים את הרשימה בזמן המעבר עליה
        synchronized (connections) {
            for (ConnectionHandler ch : connections.values()) {
                if (ch != null && ch.currentRoom.equals(room)) {
                    ch.sendMessage(message);
                }
            }
        }
    }

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
            // ignore
        }
    }

    class ConnectionHandler implements Runnable {
        private Socket client;
        private BufferedReader in;
        private PrintWriter out;
        private String nickname;
        private String currentRoom = "General";

        public ConnectionHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(client.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                
                while (true) {
                    out.println("Welcome! Enter Nickname to Login or Register: ");
                    nickname = in.readLine();
                    if (nickname == null) return;
                    nickname = nickname.trim();

                    // מחפשים את המשתמש ב-DB
                    Document existingUser = userCollection.find(new Document("username", nickname)).first();

                    if (existingUser != null) {
                        // משתמש קיים -> תהליך Login
                        out.println("Enter password for " + nickname + ": ");
                        String password = in.readLine();
                        String hashed = existingUser.getString("password");

                        if (BCrypt.checkpw(password, hashed)) {
                            // סיסמה נכונה - אבל האם הוא כבר מחובר כרגע?
                            if (isAlreadyLoggedIn(nickname)) {
                                out.println("User is already logged in from another device!");
                                continue;
                            }
                            break; // הצלחה!
                        } else {
                            out.println("Wrong password! Try again.");
                        }
                    } else {
                        // משתמש חדש -> תהליך Register
                        out.println("Nickname not found. Create a new password to register: ");
                        String newPassword = in.readLine();
                        if (newPassword != null && newPassword.length() >= 4) {
                            String hashed = BCrypt.hashpw(newPassword, BCrypt.gensalt());
                            userCollection.insertOne(new Document("username", nickname).append("password", hashed));
                            out.println("Registration successful!");
                            break;
                        } else {
                            out.println("Password too short! Try again.");
                        }
                    }
                }
                connections.put(nickname, this);
                logger.info(nickname + " connected!");
                broadcast(nickname + " joined the chat!", currentRoom);

                // בונוס: שליחת 10 הודעות אחרונות מה-DB למשתמש החדש
                pool.execute(() -> sendChatHistory());
                
                String message;
                while ((message = in.readLine()) != null) {
                	if (message.startsWith("/msg ")) {
                	    // פורמט: /msg nickname message content
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
                	    String[] split = message.split(" ", 2);
                	    if (split.length == 2) {
                	        String newRoom = split[1].trim();
                	        
                	        // מודיעים לחדר הישן שעזבנו
                	        broadcast(nickname + " left the room.", currentRoom);
                	        
                	        // עוברים חדר
                	        currentRoom = newRoom;
                	        
                	        // מודיעים לחדר החדש שהצטרפנו
                	        broadcast(nickname + " joined the room: " + currentRoom, currentRoom);
                	        sendMessage("[System] Switched to room: " + currentRoom);
                	        
                	        // (בונוס) טעינת היסטוריה ספציפית לחדר
                	        pool.execute(() -> sendChatHistory());
                	    }
                	}
                	else if (message.startsWith("/nick ")) {
                        String[] messageSplit = message.split(" ", 2);
                        if (messageSplit.length == 2) {
                            broadcast(nickname + " changed to " + messageSplit[1], currentRoom);
                            nickname = messageSplit[1];
                        }
                    } else if (message.startsWith("/quit")) {
                        break;
                    } else {
                        // הפצה לכולם ושמירה בבסיס הנתונים
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

        // שליפת היסטוריה מה-MongoDB
        private void sendChatHistory() {
            try {
            	Document filter = new Document("room", currentRoom);
                out.println("--- Last 10 messages ---" + currentRoom + " ---");
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
        

        public void sendPrivateMessage(String senderNick, String targetNick, String message) {
            ConnectionHandler target = connections.get(targetNick);
            if (target != null) {
                target.sendMessage("[Private from " + senderNick + "]: " + message);
                // נשלח אישור גם לשולח
                connections.get(senderNick).sendMessage("[Private to " + targetNick + "]: " + message);
            } else {
                connections.get(senderNick).sendMessage("[System] User " + targetNick + " not found.");
            }
        }
        
        private boolean isAlreadyLoggedIn(String name) {
        	ConnectionHandler existing = connections.get(name.toLowerCase());
        	if (existing != null && existing != this)
        		return true;
        	return false;
        }

        private void handleDisconnect() {
            // אנחנו מסירים מהמפה רק אם הניקניימ אינו נאל
            if (nickname != null) {
                connections.remove(nickname);
                broadcast(nickname + " left the chat!", currentRoom);
                logger.info("User {} disconnected", nickname);
            }
            shutdown();
        }

        public void shutdown() {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (!client.isClosed()) client.close();
            } catch (IOException e) { }
        }
    }

    public static void main(String[] args) {
        Server server = new Server();
        server.run();
    }
}