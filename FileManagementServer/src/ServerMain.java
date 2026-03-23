import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ServerMain {

    public static void main(String[] args) {

        try {
            ServerSocket serverSocket = new ServerSocket(5001);
            System.out.println("Server started on port 5001...");

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Client connected!");

                new ClientHandler(socket).start();
            }

        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }
}
