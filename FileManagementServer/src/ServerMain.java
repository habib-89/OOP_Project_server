import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ServerMain {

    private static volatile boolean running = true;

    public static void main(String[] args) {


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            System.out.println("Server shutting down...");
        }));

        try (ServerSocket serverSocket = new ServerSocket(5001)) {
            System.out.println("Server started on port 5001...");

            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    System.out.println("Client connected: " +
                            socket.getInetAddress().getHostAddress());
                    new ClientHandler(socket).start();
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error accepting client: " + e.getMessage());
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}