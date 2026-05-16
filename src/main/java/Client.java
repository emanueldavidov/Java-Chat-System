import java.io.*;
import java.net.*;
import java.util.Scanner;
import javax.net.ssl.SSLSocketFactory;

public class Client implements Runnable {
    private Socket client;
    private BufferedReader in;
    private PrintWriter out;
    private boolean done;

    @Override
    public void run() {
        try {
            // Configure SSL properties for secure communication
            String truststorePath = System.getenv("SSL_TRUSTSTORE_PATH"); 
            String truststorePass = System.getenv("SSL_TRUSTSTORE_PASSWORD");

            if (truststorePath != null && truststorePass != null) {
                System.setProperty("javax.net.ssl.trustStore", truststorePath);
                System.setProperty("javax.net.ssl.trustStorePassword", truststorePass);
            }

            // Fetch the Server IP/Host from environment variables (defaults to localhost)
            String serverHost = System.getenv("SERVER_HOST");
            if (serverHost == null) serverHost = "localhost";

            // Establish connection to the server on port 9999
            String disableSSL = System.getProperty("DISABLE_SSL", System.getenv("DISABLE_SSL"));
            if("true".equalsIgnoreCase(disableSSL)){
                client = new Socket(serverHost, 9999);
            }
            else {
                client = SSLSocketFactory.getDefault().createSocket(serverHost, 9999);
            }
            System.out.println("raw TCP Connection established. Starting TLS Handshake...");

            // אילוץ של לחיצת היד המאובטחת באופן ידני
            ((javax.net.ssl.SSLSocket) client).startHandshake();

            System.out.println("✅ TLS Handshake completed successfully! Connection is secure.");
            out = new PrintWriter(client.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(client.getInputStream()));

            // Start a background thread to listen for incoming messages from the server
            Thread inputThread = new Thread(new InputHandler());
            inputThread.start();

            // Handle user input from the console and send it to the server
            try (Scanner scanner = new Scanner(System.in)) {
                while (!done) {
                    if (scanner.hasNextLine()) {
                        String message = scanner.nextLine();
                        out.println(message);
                        
                        // Check if the user wants to terminate the connection
                        if (message.equals("/quit")) {
                            done = true;
                            shutdown();
                        }
                    }
                }
            }
        } catch (IOException e) {
            shutdown();
        }
    }

    /**
     * Gracefully closes all resources and stops the client process.
     */
    public void shutdown() {
        done = true;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (client != null && !client.isClosed()) {
                client.close();
            }
        } catch (IOException e) {
            // Ignore closure exceptions
        }
    }

    /**
     * Inner class responsible for listening to the server's output stream.
     * Runs on a separate thread to ensure the client can receive messages 
     * while simultaneously waiting for user input.
     */
    class InputHandler implements Runnable {
        @Override
        public void run() {
            try {
                String message;
                // Continuously read messages until the connection is lost or closed
                while ((message = in.readLine()) != null) {
                    System.out.println(message);
                }
            } catch (IOException e) {
                // Connection lost or closed by the server
                shutdown();
            }
        }
    }

    public static void main(String[] args) {
        Client client = new Client();
        client.run();
    }
}