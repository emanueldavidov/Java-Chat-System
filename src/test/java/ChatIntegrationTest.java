import org.junit.jupiter.api.*;
import java.io.*;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the TCP Chat Server.
 * These tests verify the full flow: connection, authentication, and messaging.
 */
public class ChatIntegrationTest {

    private static Thread serverThread;
    private Socket testClient;
    private PrintWriter out;
    private BufferedReader in;

    @BeforeAll
    static void startServer() {
        // Disable SSL for testing purposes to avoid handshake issues in the test environment
        System.setProperty("DISABLE_SSL", "true");
        
        // Run the server in a separate background thread before all tests start
        serverThread = new Thread(() -> {
            Server server = new Server();
            server.run();
        });
        serverThread.start();
        
        // Give the server a second to initialize and bind to the port
        try { Thread.sleep(1000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @BeforeEach
    void connect() throws IOException {
        // Connect to the local server before every single test case
        testClient = new Socket("localhost", 9999);
        out = new PrintWriter(testClient.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(testClient.getInputStream()));
    }

    @AfterEach
    void disconnect() throws IOException {
        // Clean up the connection after each test
        if (testClient != null && !testClient.isClosed()) {
            testClient.close();
        }
    }

    @Test
    @DisplayName("Should receive welcome message on connection")
    void testConnectionGreeting() throws IOException {
        String greeting = in.readLine();
        assertEquals("Welcome! Enter Nickname to Login or Register: ", greeting);
    }
    
    @Test
    @DisplayName("Should fail registration with short password")
    void testShortPasswordRegistration() throws IOException {
        in.readLine(); // Read initial Welcome message
        out.println("test_user_" + System.currentTimeMillis()); // Use unique nickname
        
        in.readLine(); // Read "Nickname not found..." prompt
        out.println("123"); // Send a password that is too short
        
        String response = in.readLine();
        assertEquals("Password too short! Try again.", response);
    }
    
    @Test
    @DisplayName("Should login successfully after registration")
    void testSuccessfulLogin() throws IOException {
        String user = "user_" + System.currentTimeMillis();
        String pass = "password123";

        // Step 1: Registration Flow
        in.readLine(); // Welcome
        out.println(user);
        in.readLine(); // Nickname not found...
        out.println(pass);
        in.readLine(); // Registration successful
        testClient.close(); // Disconnect to simulate a new session

        // Step 2: Login Flow (re-connecting)
        connect(); 
        in.readLine(); // Welcome
        out.println(user);
        in.readLine(); // Enter password for...
        out.println(pass);
        
        String response = in.readLine();
        // The server sends a join broadcast upon successful login
        assertTrue(response.contains("joined the chat"));
    }
    
    @Test
    @DisplayName("Private message should only be seen by target user")
    void testPrivateMessage() throws IOException, InterruptedException {
        // 1. Generate unique names to avoid MongoDB unique constraint collisions
        String sender = "s_" + System.currentTimeMillis();
        String receiver = "r_" + System.currentTimeMillis();

        // Connect Sender (User A)
        in.readLine(); out.println(sender); 
        in.readLine(); out.println("pass"); 
        in.readLine(); // Registration successful

        // Connect Receiver (User B) manually
        Socket clientB = new Socket("localhost", 9999);
        PrintWriter outB = new PrintWriter(clientB.getOutputStream(), true);
        BufferedReader inB = new BufferedReader(new InputStreamReader(clientB.getInputStream()));
        
        inB.readLine(); outB.println(receiver); 
        inB.readLine(); outB.println("pass"); 
        inB.readLine(); // Registration successful

        // Small delay to ensure both users are registered in the server's connection map
        Thread.sleep(200);

        // 2. Send private message from User A to User B
        String secretMessage = "Hello Receiver " + System.currentTimeMillis();
        out.println("/msg " + receiver + " " + secretMessage);

        // 3. Polling logic to find the private message in User B's stream
        boolean found = false;
        for (int i = 0; i < 20; i++) { // Check up to 20 incoming lines
            if (inB.ready()) { // Check if data is waiting in the buffer
                String line = inB.readLine();
                if (line.contains(secretMessage) && line.contains("[Private from " + sender + "]")) {
                    found = true;
                    break;
                }
            } else {
                Thread.sleep(100); // Wait briefly if buffer is empty
            }
        }

        assertTrue(found, "The private message was not received by the target user within the timeout.");
        clientB.close();
    }
}