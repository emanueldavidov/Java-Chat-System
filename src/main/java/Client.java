import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client implements Runnable {
    private Socket client;
    private BufferedReader in;
    private PrintWriter out;
    private boolean done;

    @Override
    public void run() {
        try {
        	String serverHost = System.getenv("SERVER_HOST");
        	if (serverHost == null) serverHost = "localhost";

        	client = new Socket(serverHost, 9999);
            out = new PrintWriter(client.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(client.getInputStream()));

            // ת'רד לקריאת הודעות מהשרת
            Thread inputThread = new Thread(new InputHandler());
            inputThread.start();

            // קריאת קלט מהמשתמש ושליחה לשרת
            Scanner scanner = new Scanner(System.in);
            while (!done) {
                if (scanner.hasNextLine()) {
                    String message = scanner.nextLine();
                    out.println(message);
                    if (message.equals("/quit")) {
                        done = true;
                        shutdown();
                    }
                }
            }
        } catch (IOException e) {
            shutdown();
        }
    }

    public void shutdown() {
        done = true;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (client != null && !client.isClosed()) client.close();
        } catch (IOException e) { }
    }

    // מחלקה פנימית לטיפול בהודעות נכנסות מהשרת
    class InputHandler implements Runnable {
        @Override
        public void run() {
            try {
                String message;
                while ((message = in.readLine()) != null) {
                    System.out.println(message);
                }
            } catch (IOException e) {
                shutdown();
            }
        }
    }

    public static void main(String[] args) {
        Client client = new Client();
        client.run();
    }
}