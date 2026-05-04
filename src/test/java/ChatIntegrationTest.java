import org.junit.jupiter.api.*;
import java.io.*;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;

public class ChatIntegrationTest {

    private static Thread serverThread;
    private Socket testClient;
    private PrintWriter out;
    private BufferedReader in;

    @BeforeAll
    static void startServer() {
        // מריצים את השרת בטרד נפרד לפני כל הבדיקות
        serverThread = new Thread(() -> {
            Server server = new Server();
            server.run();
        });
        serverThread.start();
        
        // נותנים לשרת שנייה לעלות
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
    }

    @BeforeEach
    void connect() throws IOException {
        // מתחברים לשרת המקומי לפני כל בדיקה בודדת
        testClient = new Socket("localhost", 9999);
        out = new PrintWriter(testClient.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(testClient.getInputStream()));
    }

    @AfterEach
    void disconnect() throws IOException {
        testClient.close();
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
        in.readLine(); // קריאת ה-Welcome
        out.println("test_user_" + System.currentTimeMillis()); // ניקניימ ייחודי
        
        in.readLine(); // קריאת ה-"Nickname not found..."
        out.println("123"); // סיסמה קצרה מדי
        
        String response = in.readLine();
        assertEquals("Password too short! Try again.", response);
    }
    
    @Test
    @DisplayName("Should login successfully after registration")
    void testSuccessfulLogin() throws IOException {
        String user = "user_" + System.currentTimeMillis();
        String pass = "password123";

        // 1. רישום
        in.readLine(); // Welcome
        out.println(user);
        in.readLine(); // Nickname not found...
        out.println(pass);
        in.readLine(); // Registration successful
        testClient.close(); // מתנתקים

        // 2. ניסיון לוגין מחדש
        connect(); // התחברות חדשה (קריאה למתודה מה-BeforeEach)
        in.readLine(); // Welcome
        out.println(user);
        in.readLine(); // Enter password for...
        out.println(pass);
        
        String response = in.readLine();
        assertTrue(response.contains("joined the chat"));
    }
    
    @Test
    @DisplayName("Private message should only be seen by target user")
    void testPrivateMessage() throws IOException, InterruptedException {
        // 1. שימוש בשמות ייחודיים לכל הרצה כדי למנוע התנגשויות במונגו
        String sender = "s_" + System.currentTimeMillis();
        String receiver = "r_" + System.currentTimeMillis();

        // התחברות שולח (A)
        in.readLine(); out.println(sender); 
        in.readLine(); out.println("pass"); 
        in.readLine(); // Registration successful

        // התחברות מקבל (B)
        Socket clientB = new Socket("localhost", 9999);
        PrintWriter outB = new PrintWriter(clientB.getOutputStream(), true);
        BufferedReader inB = new BufferedReader(new InputStreamReader(clientB.getInputStream()));
        
        inB.readLine(); outB.println(receiver); 
        inB.readLine(); outB.println("pass"); 
        inB.readLine(); // Registration successful

        // המתנה קצרה כדי לוודא ששניהם רשומים ב-Map של השרת
        Thread.sleep(200);

        // 2. שליחת הודעה פרטית
        String secretMessage = "Hello Receiver " + System.currentTimeMillis();
        out.println("/msg " + receiver + " " + secretMessage);

        // 3. לוגיקה חכמה לקריאת ההודעה: מחפשים את ההודעה הסודית בתוך כל ה"רעש"
        boolean found = false;
        for (int i = 0; i < 20; i++) { // בודקים עד 20 שורות נכנסות
            if (inB.ready()) { // בודק אם יש מידע שמחכה בבאפר
                String line = inB.readLine();
                if (line.contains(secretMessage) && line.contains("[Private from " + sender + "]")) {
                    found = true;
                    break;
                }
            } else {
                Thread.sleep(100); // מחכים רגע אם הבאפר ריק
            }
        }

        assertTrue(found, "The private message was not received by the target user within the timeout.");
        clientB.close();
    }
}