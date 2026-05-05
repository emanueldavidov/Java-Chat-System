import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LoadTester: Simulates multiple concurrent clients to stress test the server.
 * Use this to verify how the server handles high traffic and to visualize metrics in Grafana.
 */
public class LoadTester {
    private static final String SERVER_IP = "localhost";
    private static final int SERVER_PORT = 9999;
    private static final int CLIENT_COUNT = 200; // Number of simulated users
    private static final int MESSAGES_PER_CLIENT = 1; // Messages sent by each bot
    private static final AtomicInteger successfulConnections = new AtomicInteger(0);

    public static void main(String[] args) {
        System.out.println("Starting Stress Test with " + CLIENT_COUNT + " clients...");
        
        // Create a Thread Pool to run all clients in parallel
        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_COUNT);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < CLIENT_COUNT; i++) {
            final int clientId = i;
            executor.execute(() -> simulateClient(clientId));
        }

        // Standard shutdown sequence for the executor service
        executor.shutdown();
        while (!executor.isTerminated()) {
            // Busy-wait or sleep until all threads complete their tasks
        }

        long endTime = System.currentTimeMillis();
        System.out.println("--- Test Results ---");
        System.out.println("Total Clients: " + CLIENT_COUNT);
        System.out.println("Successful Connections: " + successfulConnections.get());
        System.out.println("Total Time: " + (endTime - startTime) + " ms");
        System.out.println("Check Grafana for metrics!");
    }

    /**
     * Simulates a single client session: connects, authenticates, sends messages, and stays alive.
     */
    private static void simulateClient(int id) {
        try (Socket socket = new Socket(SERVER_IP, SERVER_PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // 1. Authentication Phase (Simulating nickname and password entry)
            String nickname = "bot_" + id;
            
            // The server asks for nickname; bot sends it
            out.println(nickname); 
            
            // The server asks for password (either registration or login); bot sends it
            out.println("pass1234"); 

            successfulConnections.incrementAndGet();

            // 2. Messaging Phase
            for (int j = 0; j < MESSAGES_PER_CLIENT; j++) {
                out.println("Load test message #" + j + " from " + nickname);
                // Small delay between messages to simulate human-ish typing speed
                Thread.sleep(100); 
            }

            // 3. Keep-Alive Phase
            // We keep the connection open for 60 seconds to observe the "Online Users" Gauge in Grafana
            Thread.sleep(60000); 
            
        } catch (Exception e) {
            System.err.println("Client " + id + " failed: " + e.getMessage());
        }
    }
}